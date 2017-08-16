package org.protocols.register

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.ActorRef

import scala.collection.immutable.Nil
import scala.language.postfixOps

/**
  * Register-based messages
  */
abstract sealed class RegisterMessage {
  // An actor to send this message to
  def dest: ActorRef
}

final case class READ(cid: ActorRef, dest: ActorRef, k: Int) extends RegisterMessage
final case class ackREAD(j: ActorRef, dest: ActorRef, k: Int, kWv: Option[(Int, Any)]) extends RegisterMessage
final case class nackREAD(j: ActorRef, dest: ActorRef, k: Int, kWv: Option[(Int, Any)]) extends RegisterMessage

final case class WRITE(cid: ActorRef, dest: ActorRef, k: Int, vW: Any) extends RegisterMessage
final case class ackWRITE(j: ActorRef, dest: ActorRef, k: Int) extends RegisterMessage
final case class nackWRITE(j: ActorRef, dest: ActorRef, k: Int) extends RegisterMessage

case class MessageToProxy(rm: RegisterMessage, params: Any)

/**
  * @param acceptors     identifiers of acceptors to communicate with through the proxy
  * @param k             my proposer's ballot
  * @param contextParams parameters per this instance, passed to the proxy (e.g., a slot)
  *
  *
  *                      Here, the "point-cut" is "acceptors ? ..." which knows how to redirect the message.
  *                      In the future, cid will be responsible for attaching extra information, like, e.g., a slot.
  *
  *                      So getRegister(s) implemented somewhere else will make sure that cid dispatches the request
  *                      to the correct slot.
  */
class RoundBasedRegister[T](private val acceptors: Seq[ActorRef],
                            private val myProxy: ActorRef,
                            val k: Int,
                            val contextParams: Any) {

  def read(): (Boolean, Option[T]) = {
    // Send out requests
    for (j <- acceptors) yield emitMsg(READ(self, j, k))
    Thread.sleep(timeoutMillis)

    // Collect responses
    var maxKW = 0
    var maxV: Option[T] = None
    var responses = 0
    processMessages {
      case m@ackREAD(j, _, `k`, kWv) =>
        //        println(s"[READ] Received: $m")
        responses = responses + 1
        kWv match {
          case Some((kW, v)) if kW >= maxKW =>
            maxKW = kW
            maxV = Some(v.asInstanceOf[T])
          case _ =>
        }

      case nackREAD(j, _, `k`, kWv) =>
        // Learn the value anyway
        kWv match {
          case Some((kW, v)) if kW >= maxKW =>
            maxKW = kW
            maxV = Some(v.asInstanceOf[T])
          case _ =>
        }
      // return (false, None)
    }

    if (responses >= Math.ceil((acceptors.size + 1) / 2)) (true, maxV) else (false, maxV)
  }

  private def write(vW: T): Boolean = {
    // Send out proposals
    for (j <- acceptors) yield emitMsg(WRITE(self, j, k, vW))
    Thread.sleep(timeoutMillis)

    // Collect responses
    var responses = 0
    processMessages {
      case m@ackWRITE(j, _, `k`) =>
        //        println(s"[WRITE] Received: $m")
        responses = responses + 1
        if (responses >= Math.ceil((acceptors.size + 1) / 2)) {
          return true
        }
      case nackWRITE(j, _, `k`) => return false
    }
    false
  }

  def propose(v0: T): Option[T] = {
    val readResult = read()
    readResult match {
      case (true, vOpt) =>
        val vW = if (vOpt.isEmpty) v0 else vOpt.get
        val res = write(vW)
        if (res) Some(vW) else None
      case (false, _) => None
    }
  }


  /** ****************************************************************************
    * Utility methods and auixiliary fields
    * ****************************************************************************/

  private val myMessageQueue: ConcurrentLinkedQueue[Any] = new ConcurrentLinkedQueue[Any]()
  private val timeoutMillis = 100
  private val self: ActorRef = myProxy // Middleman for virtualisation

  private def emitMsg(msg: RegisterMessage): Unit = self ! MessageToProxy(msg, contextParams)

  def putMsg(msg: Any): Unit = myMessageQueue.add(msg)

  // resorting to shared memory concruuency
  private def processMessages(f: PartialFunction[Any, Unit]): Unit = {
    val iter = myMessageQueue.iterator()
    while (iter.hasNext) {
      val msg = iter.next()
      if (f.isDefinedAt(msg)) {
        iter.remove()
        f(msg)
      }
    }
  }

}

/**
  * An acceptor STS
  *
  * @param initRead Initial ballot to start from
  */
class AcceptorForRegister(val self: ActorRef,
                          private val initRead: Int = 0) {

  type Ballot = Int
  type ToSend = RegisterMessage
  type Step = PartialFunction[Any, RegisterMessage]

  var read: Ballot = initRead
  var chosenValues: List[(Ballot, Any)] = Nil

  val step: Step = {
    case m@READ(cid, `self`, k) =>
      // Using non-strict inequality here for multi-paxos
      if (read >= k) {
        emitMsg(nackREAD(self, cid, k, findMaxBallotAccepted(chosenValues)))
      } else {
        bumpUpBallot(k)
        emitMsg(ackREAD(self, cid, k, findMaxBallotAccepted(chosenValues)))
      }

    case WRITE(cid, `self`, k, vW) =>
      if (read > k) {
        emitMsg(nackWRITE(self, cid, k))
      } else {
        // record the value
        chosenValues = (k, vW) :: chosenValues
        read = k
        // we may even ignore this step
        emitMsg(ackWRITE(self, cid, k))
      }
  }

  // This method is _always_ safe to run, as it only reduces the set of Acceptor's behaviors
  def bumpUpBallot(b: Ballot): Unit = {
    if (b > read) {
      read = b
    }
  }

  // Some library functions
  def findMaxBallotAccepted(chosenValues: List[(Ballot, Any)]): Option[(Ballot, Any)] =
    chosenValues match {
      case Nil => None
      case x => Some(x.maxBy(_._1))
    }

  // Adapt the message for the wrapping combinator
  private def emitMsg(msg: RegisterMessage) = msg

}






