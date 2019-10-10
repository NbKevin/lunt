package e.lent

import akka.actor.ActorRef

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import java.io.Serializable

import com.google .protobuf.ByteString


/**
  * Created by Nb on 26/06/2017.
  */

object Trie {
  val ROUTING_TABLE_SIZE = 36 // as of the size of english alphabet
  val MAX_OUTSOURCE_SIZE = 3

  def newNode (value: Option[ByteString], parentNode: TrieNode, key: Char): TrieNode =
    new TrieNode (ROUTING_TABLE_SIZE, value, parentNode, key)
}

object TrieLookupMessage {
  sealed abstract class LookupResult
  final case class Succeeded (answer: ByteString) extends LookupResult
  final case class RequireSecondaryLookup (fromNode: TrieNode, fromRoutingEntry: Option[RoutingEntry])
    extends LookupResult
}

class TrieNode (val routingTableSize: Int, var value: Option[ByteString],
                val parentNode: TrieNode, val key: Char) {
  val routingTable = new mutable.HashMap[Char, RoutingEntry]()

  def lookup (what: String, beginningWhere: Int): TrieLookupMessage.LookupResult = {
    // if we've reached the end of the concerned key, check if there is any value
    // associated with the node, if not, then secondary lookup is required
    if (beginningWhere == what.length)
      value match {
        case Some (answer) => return TrieLookupMessage.Succeeded (answer)
        case _ => return TrieLookupMessage.RequireSecondaryLookup (this, null)
      }

    // if we haven't reached the end yet, first check if there is any routing
    // entry associated with the concerned character
    routingTable.get (what.charAt (beginningWhere)) match {
      case Some (routingEntry) =>
        // if there is an routing entry, check if there is any child node associated
        // if yes, dispatch the lookup to the child node, else, issue secondary lookup
        routingEntry.childNode match {
          case Some (childNode) => childNode.lookup (what, beginningWhere + 1)
          case _ => TrieLookupMessage.RequireSecondaryLookup (this, Some (routingEntry))
        }
      case _ =>
        // if there is no routing entry associated, then issue secondary lookup
        TrieLookupMessage.RequireSecondaryLookup (this, null)
    }
  }

  def insert (what: String, isWhat: ByteString): Unit = {
    insert (what, isWhat, 0)
  }

  def insert (what: String, isWhat: ByteString, beginningWhere: Int): Unit = {
    // when we've reached the very bottom, insert the value
    if (beginningWhere == what.length) value = Some (isWhat)
    else {
      // otherwise try get the next target child node and call insert
      // on it, if the child node or the routing entry does not exist
      // create it
      val currentKey = what.charAt (beginningWhere)
      if (!routingTable.contains (currentKey)) {
        val newNode = new TrieNode (Trie.ROUTING_TABLE_SIZE, null, this, currentKey)
        routingTable.put (currentKey, new RoutingEntry (Some (newNode), Trie.MAX_OUTSOURCE_SIZE))
        newNode.insert (what, isWhat, beginningWhere + 1)
      } else {
        routingTable.get (currentKey) match {
          case Some (routingEntry) =>
            routingEntry.childNode match {
              case Some (childNode) =>
                childNode.insert (what, isWhat, beginningWhere + 1)
              case _ =>
                val newChildNode = new TrieNode (Trie.ROUTING_TABLE_SIZE, null, this, currentKey)
                routingEntry.childNode = Some (newChildNode)
                newChildNode.insert (what, isWhat, beginningWhere + 1)
            }
          // DONE: fix no routing entry situation
          case _ =>
            // if even the routing entry does not exist, create all of them
            val newChildNode = Trie.newNode (null, this, currentKey)
            val newRoutingEntry = RoutingEntry (Some (newChildNode))
            routingTable += ((currentKey, newRoutingEntry))
            newChildNode.insert (what, isWhat, beginningWhere + 1)
        }
      }
    }
  }

  /** add an outsource */
  def addOutsource (what: String, to: ActorRef, beginWhere: Int): Unit = {
    if (beginWhere == what.length - 1) {
      // add the outsource into this node's routing entry
      val lastKey = what.charAt (beginWhere)
      routingTable.get (lastKey) match {
        case Some (routingEntry) => routingEntry.addOutsource (to)
        case _ =>
          // create a routing entry and insert the outsource
          val newRoutingEntry = new RoutingEntry (null, Trie.MAX_OUTSOURCE_SIZE)
          newRoutingEntry.addOutsource (to)
          routingTable += ((lastKey, newRoutingEntry))
      }
    } else {
      // create the routing entry and the child node if it does not exist
      // otherwise call add outsource upon the child node
      val currentKey = what.charAt (beginWhere)
      routingTable.get (currentKey) match {
        case Some (routingEntry) =>
          routingEntry.childNode match {
            case Some (childNode) =>
              childNode.addOutsource (what, to, beginWhere + 1)
            case _ =>
              // create the child node
              val newChildNode = new TrieNode (Trie.ROUTING_TABLE_SIZE, null, this, currentKey)
              routingEntry.childNode = Some (newChildNode)
              newChildNode.addOutsource (what, to, beginWhere + 1)
          }
        case _ =>
          // create the routing entry first and since the routing entry was
          // just created there is certainly no child node, so create it as well
          val newChildNode = new TrieNode (Trie.ROUTING_TABLE_SIZE, null, this, currentKey)
          val newRoutingEntry = new RoutingEntry (Some (newChildNode), Trie.MAX_OUTSOURCE_SIZE)
          routingTable += ((currentKey, newRoutingEntry))
          newChildNode.addOutsource (what, to, beginWhere + 1)
      }
    }
  }

  def addOutsource (what: String, to: ActorRef): Unit = addOutsource (what, to, 0)
}

object SentinelParent extends TrieNode (Trie.ROUTING_TABLE_SIZE, null, null.asInstanceOf [TrieNode], null.asInstanceOf [Char]) {}

object RoutingEntry {
  def apply (childNode: Option[TrieNode]): RoutingEntry =
    new RoutingEntry (childNode, Trie.MAX_OUTSOURCE_SIZE)
}

class RoutingEntry (var childNode: Option[TrieNode], val maxOutsourceSize: Int) extends Serializable {
  val outsources = new ListBuffer[OutsourceRoutingEntry]()

  def isFull: Boolean = outsources.size >= maxOutsourceSize

  def addOutsource (to: ActorRef): Unit = {
    if (isFull) outsources -= outsources.minBy { entry => entry.time }
    if (outsources.exists { entry => entry.actor == to }) return
    outsources.append (new OutsourceRoutingEntry (to, System.currentTimeMillis))
  }
}

class OutsourceRoutingEntry (val actor: ActorRef, val time: Long) extends Serializable
