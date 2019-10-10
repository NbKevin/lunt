package e.lent

import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, ActorSystem, Props}

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import e.lent.util.Generate.makeRandomString
import e.lent.message.{master => MasterMessages}
import e.lent.message.{peer => PeerMessages}
import e.lent.util.SerialisationWrapper._

import com.google.protobuf.ByteString


//object MasterMessage {
//  sealed abstract class MasterMessage
//  final case class LookupFinished (answer: Serializable, id: String) extends MasterMessage
//  final case class LookupFailed (id: String) extends MasterMessage
//  final case class RegisterNewPeerByRef (peer: ActorRef) extends MasterMessage
//  final case class RegisterNewPeer (path: String) extends MasterMessage
//  final case class Lookup (what: Serializable) extends MasterMessage
//  final case class Insert (what: Serializable, isWhat: Serializable) extends MasterMessage
//}

object Master {
  def instance: Props = Props (new Master ())
}

class Master extends Actor with ActorLogging {
  /* stores all registered peers */
  private val registeredPeers = new ListBuffer[ActorRef]

  def peers: List[ActorRef] = registeredPeers.toList

  def peersAsPlainPath: List[String] = peers.map (peer => peer.serialise)

  /** add a new peer into the system! */
  def registerNewPeerByRef (newPeer: ActorRef): Unit = {
    if (registeredPeers.contains (newPeer)) return
    log.info (s"new join request: from <${newPeer.path.name}>")
    newPeer ! PeerMessages.AddBootstrapNodeOutsources (peersAsPlainPath)
    log.info (s"sent bootstrap root outsource to new peer: <${newPeer.path.name}>")
    // FIXED test if this new adaptation works
    registeredPeers.foreach {
      peer =>
        if (peer != newPeer) peer ! PeerMessages.AddRootNodeOutsource (newPeer.serialise)
      // FIXED test if this new adaptation works
    }
    log.info (s"notified ${registeredPeers.size} other peer(s) of new peer joining: <${newPeer.path.name}>")
    registeredPeers.append (newPeer)
    log.info (s"new peer joined the network: <${newPeer.serialise}>")
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

  /**
    * Look up something. Since this is an internal method to the master,
    * no support for arbitrary key type is necessary. Hashing is done
    * upon receiving a look up message instead of here.
    *
    * @param what hash name of whatever to look up
    */
  def lookup (what: String): Unit = {
    log.debug (s"looking up key <$what>")
    if (noRegisteredPeer)
      log.warning ("no registered peer to lookup from, requested disposed")
    else
      registeredPeers (Random.nextInt (registeredPeers.length)) ! PeerMessages.Lookup (what, makeRandomString (7))
  }

  /**
    * Insert something. Similarly no support for arbitrary data type is
    * done since the hashing should be done already upon receiving the message.
    *
    * @param what   hash name
    * @param isWhat content
    */
  def insert (what: String, isWhat: ByteString): Unit = {
    if (noRegisteredPeer)
      log.warning ("no registered peer to lookup from, requested disposed")
    else
      registeredPeers (Random.nextInt (registeredPeers.length)) ! PeerMessages.Insert (what.toString, isWhat)
  }

  override def receive: Receive = {
    case MasterMessages.LookupFinished (id, answer) =>
      log.info (s"lookup finished #$id: answer is <$answer>")
    case MasterMessages.LookupFailed (id) =>
      log.info (s"lookup failed #$id")
    case MasterMessages.RegisterNewPeerByPath (peer) =>
      log.info (s"incoming request: register peer <$peer> by ref")
      registerNewPeer (peer)
    // FIXED in fact we don't need two separate peer now right?
    case MasterMessages.RegisterNewPeer (path) =>
      log.info (s"incoming request: register peer <$path>")
      registerNewPeer (path)
    case MasterMessages.Lookup (what) =>
      log.info (s"incoming request: look up <$what>")
      lookup (what)
    case MasterMessages.Insert (what, isWhat) =>
      log.info (s"inserting data: <$what> => <$isWhat>")
      insert (what, isWhat)
    case _ =>
      log.warning ("unknown message")
  }
}
