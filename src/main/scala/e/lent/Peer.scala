package e.lent

/**
  * Represents a single node.
  */

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import e.lent.util.Hash.hash
import e.lent.message.{peer => PeerMessages}
import e.lent.message.{master => MasterMessages}
import e.lent.util.SerialisationWrapper._


//object PeerMessage {
//  /**
//    * Exhaustive message cases for incoming requests for peers.
//    */
//  sealed abstract class IncomingPeerRequest
//  final case class Lookup (what: String, id: String) extends IncomingPeerRequest
//  final case class SecondaryLookup (what: String, id: String, backWhere: ActorRef) extends IncomingPeerRequest
//  final case class Insert (what: String, isWhat: Serializable) extends IncomingPeerRequest
//  final case class SecondaryLookupResponse (result: Peer.SecondaryLookupResult.Result) extends IncomingPeerRequest with Serializable
//  final case class AddRootNodeOutsource (peer: ActorRef) extends IncomingPeerRequest
//  final case class SetBootstrapRootOutsource (bootstrapRootOutsources: List[ActorRef]) extends IncomingPeerRequest
//  final case class ConnectToMaster (path: String) extends IncomingPeerRequest
//  final case class ConnectedToMaster () extends IncomingPeerRequest
//}

object Peer {
  /**
    * Make a new peer.
    *
    * @param name      a name, for identification, not necessarily the same as the actor name
    * @param masterRef a reference to the master actor
    * @return a safely instantiated new peer
    */
  def makeNew (name: String, masterRef: ActorRef): Props =
    Props (new Peer (name, masterRef))

  /**
    * Exhaustive message cases for secondary lookup results.
    */
  object SecondaryLookupResult {
    sealed abstract class Result
    case class Failed (id: String) extends Result
    case class Succeeded (result: Serializable, fromWhere: ActorRef, id: String) extends Result
    case class NeedFurtherLookup (routingTable: RoutingEntry, id: String) extends Result
  }
}

/**
  * Represents a peer. Never instantiate new peers through raw constructors.
  * Always use [[e.lent.Peer.makeNew]] instead.
  *
  * @param name      a name for identification
  * @param masterRef a reference to the master actor.
  */
class Peer (val name: String, var masterRef: ActorRef) extends Actor with ActorLogging {
  if (masterRef == null) log.warning ("be careful that peer is booted with no reference to master")

  /** dispatch messages */
  override def receive: Receive = {
    case PeerMessages.Lookup (what, id) =>
      log.debug (s"incoming request #$id: lookup <$what>")
      lookup (what, id)
    case PeerMessages.Insert (what, isWhat) =>
      log.debug (s"incoming request: insert <$what => $isWhat>")
      rootNode.insert (what, isWhat)
    case PeerMessages.SecondaryLookupResponse (result) =>
      log.debug (s"incoming response: secondary lookup completed <$result>")
      processSecondaryLookupResponse (result) // TODO fix this
    case PeerMessages.AddBootstrapNodeOutsources (bootstrapRootOutsources) =>
      log.debug (s"incoming request: bootstrap root outsource <size ${bootstrapRootOutsources.size}>")
      setBootstrapRootOutsources (bootstrapRootOutsources.map (
        _.deserialiseWithContext (context))) // TODO fix this
    case PeerMessages.AddRootNodeOutsource (peer) =>
      log.debug (s"incoming request: add peer to root outsource <$peer>")
      addRootNodeOutsource (peer.deserialiseWithContext (context)) // TODO fix this
    case PeerMessages.SecondaryLookup (id, what, backWhere) =>
      log.debug (s"incoming request #$id: secondary lookup <$what>, from <$backWhere>")
      runSecondaryLookup (what, id, backWhere.deserialiseWithContext (context)) // TODO fix this
    case PeerMessages.ConnectToMaster (path) =>
      log.debug (s"incoming request: connect to master <$path>")
      connectToMaster (path) // TODO fix this
    case _ =>
      log.warning ("unknown request")
  }

  /* connect to a master */
  def connectToMaster (path: String): Unit = {
    context.actorSelection (path).resolveOne (20 seconds) onComplete {
      case Success (actorRef) =>
        masterRef = actorRef
        actorRef ! MasterMessages.RegisterNewPeer (self.serialise)
        log.info (s"connected to master <$path>")
      case Failure (exception) => log.error (s"cannot connect to master <$path>: ${exception.getMessage}")
    }
  }

  /** root node, has a fake sentinel parent */
  val rootNode = new TrieNode (Trie.ROUTING_TABLE_SIZE, null, SentinelParent, null.asInstanceOf [Char])
  (97 to 122).foreach (ascii => rootNode.routingTable.put (ascii.toChar, new RoutingEntry (null, Trie.MAX_OUTSOURCE_SIZE)))
  (48 to 57).foreach (ascii => rootNode.routingTable.put (ascii.toChar, new RoutingEntry (null, Trie.MAX_OUTSOURCE_SIZE)))

  /** set bootstrap root outsources */
  def setBootstrapRootOutsources (bootstrapRootOutsources: TraversableOnce[ActorRef]): Unit =
    bootstrapRootOutsources.foreach { peer => addRootNodeOutsource (peer) }

  /* silently fail the lookup if responses come back with unknown ids */
  private def silentlyFailUnknownId (id: String): Unit = {
    log.error (s"secondary lookup failed because an unknown id is returned: #$id")
    masterRef.tell (MasterMessages.LookupFailed (id), self)
  }

  /* deal with secondary lookup result */
  def processSecondaryLookupResponse (result: PeerMessages.SecondaryLookupResponse.Result): Unit = {
    result match {
      case PeerMessages.SecondaryLookupResponse.Result.Failed (message) =>
        // if this particular secondary lookup failed we simply turn back to the
        // local candidates and continue secondary lookup
        log.info ("search returned no result, returning back to internal candidates")
        getSecondaryLookup (message.id) match {
          case Some (foo) =>
            issueSecondaryLookup (foo)
          case _ =>
            silentlyFailUnknownId (message.id)
        }

      case PeerMessages.SecondaryLookupResponse.Result.Succeeded (message) =>
        // if succeeded, send the answer back to master
        // and update local outsources in accordance
        log.info (s"search returned definite result: ${message.result}, #${message.id}")
        getSecondaryLookup (message.id) match {
          case Some (idAwareSecondaryLookup) =>
            secondaryLookupQueue -= idAwareSecondaryLookup
            // TODO: adjust the Succeeded message to include what and toWhere
            // FIXED: update local outsources
            val whatHash = hash (idAwareSecondaryLookup._2.what)
            rootNode.addOutsource (whatHash, message.fromWhere.deserialiseWithContext (context))
          case _ =>
            silentlyFailUnknownId (message.id)
        }
        masterRef ! MasterMessages.LookupFinished (message.id, message.result)

      case PeerMessages.SecondaryLookupResponse.Result.Redirected (message) =>
        // if a new outsource candidate is returned, update it into the
        // secondary lookup queue and initiate a new lookup
        getSecondaryLookup (message.id) match {
          case Some ((_, secondaryLookup)) =>
            secondaryLookup.outsourceRoutingEntries.appendAll (
              message.routingTable.map (_.deserialise (context)))
            issueSecondaryLookup (message.id, secondaryLookup)
          case _ =>
            silentlyFailUnknownId (message.id)
        }

      case _ =>
        // this should never happen given that the only types confronting to secondary
        // lookup responses are all tested now, all we can do is to throw an error
        throw new RuntimeException ("unknown secondary lookup response type")
    }
  }

  type IdAwareSecondaryLookup = (String, RawSecondaryLookup)

  /** stores secondary lookups issued for further reference */
  val secondaryLookupQueue = new ListBuffer[IdAwareSecondaryLookup]

  /**
    * Get the secondary lookup by its id.
    *
    * @param id id
    * @return associated secondary lookup, nullable
    */
  def getSecondaryLookup (id: String): Option[IdAwareSecondaryLookup] = {
    val filterResult = secondaryLookupQueue.filter (secondaryLookup => secondaryLookup._1 == id)
    if (filterResult.size != 1) null
    else Some (filterResult.last)
  }

  /**
    * Represents a secondary lookup, either issued or not.
    *
    * @param beginFromNode         begin from which local node
    * @param beginFromRoutingEntry begin from which routing entry
    * @param what                  lookup for what
    */
  class RawSecondaryLookup (val beginFromNode: TrieNode,
                            val beginFromRoutingEntry: Option[RoutingEntry],
                            val what: String) {
    private val lookedUpPeers = new ListBuffer[ActorRef]

    private var currentPosition = -1
    private var currentNode = beginFromNode
    private var currentRoutingEntry = beginFromRoutingEntry match {
      case Some (entry) => entry
      // this is where options get annoying somehow that I have to write
      // all of this again, missing the old, butcher C now
      case _ =>
        if (beginFromNode.parentNode == SentinelParent)
          throw new NoSuchElementException ("root node has no parent")

        // search up the trie for next non-null routing entry
        // if the given one is null
        var tempCurrentNode = beginFromNode
        var tempCurrentRoutingEntry = beginFromRoutingEntry
        do {
          val targetingChar = tempCurrentNode.key
          tempCurrentNode = tempCurrentNode.parentNode
          tempCurrentRoutingEntry = tempCurrentNode.routingTable.get (targetingChar)
        } while ((tempCurrentRoutingEntry == null || tempCurrentRoutingEntry.get.outsources.size < 1)
          && tempCurrentNode.parentNode != SentinelParent)
        currentNode = tempCurrentNode
        tempCurrentRoutingEntry match {
          case Some (routingEntry) => routingEntry
          case _ => throw new RuntimeException ("this should never happen")
        }
    }

    /** outsource routing entries always come first when pulling next peer */
    var outsourceRoutingEntries: ListBuffer[OutsourceRoutingEntry] = new ListBuffer[OutsourceRoutingEntry]

    /** filter out peers that's already looked up and self */
    def nextPeer: Option[OutsourceRoutingEntry] = {
      var candidate = nextPeerCandidate
      while (candidate != null && (lookedUpPeers.contains (candidate.get.actor) || self == candidate.get.actor))
        candidate = nextPeerCandidate
      if (candidate != null) lookedUpPeers.append (candidate.get.actor)
      candidate
    }

    /** pull next peer from the queue, if non-existing then lookup should fail */
    private def nextPeerCandidate: Option[OutsourceRoutingEntry] = {
      // lookup to any outsource routing entry first, if any
      if (outsourceRoutingEntries.nonEmpty)
        return Some (outsourceRoutingEntries.remove (0))

      // step current position forward by 1 and see if there is any outsource
      // left in the current routing entry, if not, move upwards to its parent
      currentPosition += 1
      if (currentPosition < currentRoutingEntry.outsources.size)
        Some (currentRoutingEntry.outsources (currentPosition))
      else {
        val parentNode = currentNode.parentNode
        if (parentNode == SentinelParent || parentNode == null) {
          // this suggests that we've reached the top and no it's time for going
          // through all known peers one by one stored in the root node
          //          if (!addedRoot) {
          //            outsourceRoutingEntries.appendAll (currentNode.routingTable ('a').outsources)
          //            Some (outsourceRoutingEntries.remove (0))
          //          } else null
          null
        } else {
          // trace back to its parent node and continue search
          do {
            parentNode.routingTable.get (currentNode.key) match {
              case Some (routingEntry) =>
                currentRoutingEntry = routingEntry
                currentNode = parentNode
              case _ =>
                // this should never happen, if a child exists then its parent should
                // always have an entry pointing towards it
                throw new RuntimeException ("No this is impossible: ERR 101")
            }
          } while (currentRoutingEntry.outsources.size < 1)
          currentPosition = 0
          Some (currentRoutingEntry.outsources (currentPosition))
        }
      }
    }
  }

  /** issue a secondary lookup */
  def issueSecondaryLookup (beginFrom: TrieNode,
                            beginFromRoutingEntry: Option[RoutingEntry],
                            id: String,
                            what: String): Unit = {
    val secondaryLookup = (id, new RawSecondaryLookup (beginFrom, beginFromRoutingEntry, what))
    secondaryLookupQueue.append (secondaryLookup)
    issueSecondaryLookup (secondaryLookup)
  }

  /** issue a secondary lookup */
  def issueSecondaryLookup (idAwareSecondaryLookup: IdAwareSecondaryLookup): Unit = {
    val (id, secondaryLookup) = idAwareSecondaryLookup
    secondaryLookup.nextPeer match {
      case Some (peer) =>
        // if there is still candidate peer to issue lookup to then do it
        peer.actor.tell(PeerMessages.SecondaryLookup (id, secondaryLookup.what, self.serialise), self)
      case _ =>
        // lookup fails when no more peer's available
        secondaryLookupQueue -= idAwareSecondaryLookup
        masterRef.tell(MasterMessages.LookupFailed (id), self)
    }
  }

  /** lookup something */
  def lookup (what: String, id: String): Unit = {
    val internalLookupResult = rootNode.lookup (what, 0)
    internalLookupResult match {
      case TrieLookupMessage.Succeeded (answer) =>
        // TODO change the signature of Node::lookup(string, int): ByteString
        masterRef ! MasterMessages.LookupFinished (id, answer)
      case TrieLookupMessage.RequireSecondaryLookup (fromNode, maybeRoutingEntry) =>
        issueSecondaryLookup (fromNode, maybeRoutingEntry, id, what)
    }
  }

  /** run the requested secondary lookup */
  def runSecondaryLookup (what: String, id: String, backWhere: ActorRef): Unit = {
    val internalLookupResult = rootNode.lookup (what, 0)
    internalLookupResult match {
      case TrieLookupMessage.Succeeded (answer) =>
        backWhere ! PeerMessages.SecondaryLookupResponse ()
          .withSucceeded (PeerMessages.SecondaryLookupSucceeded (id, answer, self.serialise))
      case TrieLookupMessage.RequireSecondaryLookup (fromNode, fromRoutingEntry) =>
        fromRoutingEntry match {
          case Some (routingEntry) =>
            // return directly if the from routing entry is non empty unless it is the root node
            if (fromNode.parentNode == SentinelParent || fromNode == rootNode)
              backWhere ! PeerMessages.SecondaryLookupResponse ()
                .withFailed (PeerMessages.SecondaryLookupFailed (id))
            else
              backWhere ! PeerMessages.SecondaryLookupResponse ().withRedirected (
                PeerMessages.SecondaryLookupRedirected (id, routingEntry.outsources.map (_.serialise)))
          case _ =>
            // otherwise trace back to the routing entry with the longest common prefix
            var currentNode = fromNode
            var currentRoutingEntry = fromRoutingEntry
            while ((currentRoutingEntry == null || currentRoutingEntry.get.outsources.isEmpty)
              && currentNode.parentNode != SentinelParent) {
              currentRoutingEntry = currentNode.parentNode.routingTable.get (currentNode.key)
              currentNode = currentNode.parentNode
            }
            currentRoutingEntry match {
              // lookup fails when no available routing entry is found or the only available ones
              // are the ones of the root node, which is identical across all peers, meaning
              // it is unnecessary to return it to the caller
              case Some (routingEntry) if routingEntry.outsources.nonEmpty =>
                backWhere ! PeerMessages.SecondaryLookupResponse ().withRedirected (
                  PeerMessages.SecondaryLookupRedirected (id, routingEntry.outsources.map (_.serialise)))
              case _ =>
                backWhere ! PeerMessages.SecondaryLookupResponse ()
                  .withFailed (PeerMessages.SecondaryLookupFailed (id))
            }
        }
    }
  }

  /** add a new peer into the outsources of the root node */
  def addRootNodeOutsource (peer: ActorRef): Unit = {
    rootNode.routingTable.foreach {
      case (_, routingEntry) =>
        routingEntry.outsources.append (new OutsourceRoutingEntry (peer, System.currentTimeMillis))
    }
  }
}
