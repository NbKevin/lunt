package e.lent

import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, ActorSystem, Props}

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import e.lent.util.Generate.makeRandomString
import e.lent.message.master._
import e.lent.message.peer._
import java.io.Serializable


object MasterMessage {
  sealed abstract class MasterMessage
  final case class LookupFinished (answer: Serializable, id: String) extends MasterMessage
  final case class LookupFailed (id: String) extends MasterMessage
  final case class RegisterNewPeerByRef (peer: ActorRef) extends MasterMessage
  final case class RegisterNewPeer (path: String) extends MasterMessage
  final case class Lookup (what: Serializable) extends MasterMessage
  final case class Insert (what: Serializable, isWhat: Serializable) extends MasterMessage
}

object Master {
  def instance: Props = Props (new Master ())
}

class Master extends Actor with ActorLogging {
  /* stores all registered peers */
  private val registeredPeers = new ListBuffer[ActorRef]

  def peers: List[ActorRef] = registeredPeers.toList

  /** add a new peer into the system! */
  def registerNewPeerByRef (newPeer: ActorRef): Unit = {
    if (registeredPeers.contains (newPeer)) return
    log.info (s"new join request: from <${newPeer.path.name}>")
    newPeer ! PeerMessage.SetBootstrapRootOutsource (peers) // send bootstrap root outsources
    log.info (s"sent bootstrap root outsource to new peer: <${newPeer.path.name}>")
    registeredPeers.foreach { peer => if (peer != newPeer) peer ! PeerMessage.AddRootNodeOutsource (newPeer) }
    log.info (s"notified ${registeredPeers.size} other peer(s) of new peer joining: <${newPeer.path.name}>")
    registeredPeers.append (newPeer)
    log.info (s"new peer joined the network: <${newPeer.path.toSerializationFormat}>")
  }

  /* process the register request from a new remote peer */
  def registerNewPeer (path: String): Unit = {
    log.debug (s"new peer requesting to register: <$path>")
    context.actorSelection (path).resolveOne (20 seconds) onComplete {
      case Success (actorRef) => registerNewPeerByRef (actorRef)
      case Failure (exception) => log.error (s"unable to resolve remote peer at <$path>: ${exception.getMessage}")
    }
  }

  def noRegisteredPeer: Boolean = registeredPeers.isEmpty

  /* lookup something */
  def lookup (what: Serializable): Unit = {
    log.debug (s"looking up key <$what>")
    if (noRegisteredPeer)
      log.warning ("no registered peer to lookup from, requested disposed")
    else
      registeredPeers (Random.nextInt (registeredPeers.length)) ! PeerMessage.Lookup (what.toString, makeRandomString (7))
  }

  def insert (what: Serializable, isWhat: Serializable): Unit = {
    if (noRegisteredPeer)
      log.warning ("no registered peer to lookup from, requested disposed")
    else
      registeredPeers (Random.nextInt (registeredPeers.length)) ! PeerMessage.Insert (what.toString, isWhat)
  }

  override def receive: Receive = {
    case MasterMessage.LookupFinished (answer, id) =>
      log.info (s"lookup finished #$id: answer is <$answer>")
    case MasterMessage.LookupFailed (id) =>
      log.info (s"lookup failed #$id")
    case MasterMessage.RegisterNewPeerByRef (peer) =>
      log.info (s"incoming request: register peer <${peer.path}> by ref")
      registerNewPeerByRef (peer)
    case MasterMessage.RegisterNewPeer (path) =>
      log.info (s"incoming request: register peer <$path>")
      registerNewPeer (path)
    case MasterMessage.Lookup (what) =>
      log.info (s"incoming request: look up <$what>")
      lookup (what)
    case MasterMessage.Insert (what, isWhat) =>
      log.info (s"inserting data: <$what> => <$isWhat>")
      insert (what, isWhat)
    case _ =>
      log.warning ("unknown message")
  }
}
