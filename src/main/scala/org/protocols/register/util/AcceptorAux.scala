package org.protocols.register.util

import org.protocols.register.RegisterMessage

import scala.collection.immutable.Nil

/**
  * Utility methods and auxiliary fields for an acceptor
  */
trait AcceptorAux {

  type Ballot = Int
  type ToSend = RegisterMessage
  type Step = PartialFunction[Any, RegisterMessage]

  /** ****************************************************************************
    * Utility methods and auxiliary fields
    * ****************************************************************************/
  var read: Int

  // This method is _always_ safe to run, as it only reduces the set of Acceptor's behaviors
  def bumpUpBallot(b: Ballot): Unit = if (b > read) {
    read = b
  }

  // Some library functions
  protected def findMaxBallotAccepted(chosenValues: List[(Ballot, Any)]) = chosenValues match {
    case Nil => None
    case x => Some(x.maxBy(_._1))
  }

  // Adapt the message for the wrapping combinator
  protected def emitMsg(msg: RegisterMessage) = msg


}
