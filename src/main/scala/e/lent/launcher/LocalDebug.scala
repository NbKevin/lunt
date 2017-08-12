package e.lent.launcher

import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import e.lent.launcher.RemoteMaster.testData
import e.lent.{Master, MasterMessage, Peer, PeerMessage}
import e.lent.util.Generate.makeRandomString
import e.lent.util.Hash._

import scala.collection.mutable.ListBuffer
import scala.io.{Source, StdIn}
import scala.util.Random


/**
  * Created by Nb on 26/06/2017.
  */
object LocalDebug extends App {

  // Create the 'helloAkka' actor system
  val system: ActorSystem = ActorSystem ("dt")
  val testData = "Y:/mock.data"

  try {
    system.eventStream.setLogLevel (Logging.DebugLevel)

    val master = system.actorOf (Master.instance, "master")
    (1 to 3).foreach {
      no =>
        val newPeer = system.actorOf (Peer.makeNew (s"peer$no", master), s"peer$no")
        master ! MasterMessage.RegisterNewPeerByRef(newPeer)
    }

    Source.fromFile (testData)
      .getLines
      .foreach {
        line =>
          val grp = line.split (" ")
          val key = hash(s"${grp(0)} ${grp(1)}")
          val value = grp(2)
          master ! MasterMessage.Insert (key, value)
      }
    StdIn.readLine ()

    Source.fromFile (testData)
      .getLines
      .foreach {
        line =>
          val grp = line.split (" ")
          val key = hash(s"${grp(0)} ${grp(1)}")
          master ! MasterMessage.Lookup (key)
      }

    StdIn.readLine ()

//    val peers = new ListBuffer[ActorRef]
//    (1 to 5).foreach {
//      no =>
//        val newPeer = system.actorOf (Peer.makeNew (s"peer$no", master), s"peer$no")
//        master ! MasterMessage.RegisterNewPeerByRef (newPeer)
//        peers.append (newPeer)
//    }
//
//    Thread.sleep (1000)
//
//    val queers = new ListBuffer[(String, String)]
//    (1 to 10000).foreach {
//      _ =>
//        val key = hash(makeRandomString (15))
//        val value = makeRandomString (15)
//        queers.append ((key, value))
//        peers (Random.nextInt (peers.size)) ! PeerMessage.Insert (key, value)
//    }
//
//    Thread.sleep (2000)
//
////    val pickedKvPair = queers (Random.nextInt (queers.size))
////    val pickedPeer = peers (Random.nextInt (peers.size))
//
////    (1 to 5) foreach {
////      _ =>
////        pickedPeer ! PeerMessage.Lookup (pickedKvPair._1, makeRandomString (15))
////        Thread.sleep (1000)
////    }
////
//    (1 to 10000) foreach { _ =>
//      val (pickedKey, pickedValue) = queers (Random.nextInt (queers.size))
//      peers (Random.nextInt (peers.size)) ! PeerMessage.Lookup (pickedKey, makeRandomString (12))
//      println (s"expected: <$pickedKey => $pickedValue>")
//    }

    println (">>> Press ENTER to exit <<<")
    StdIn.readLine ()
  } finally {
    system.terminate ()
  }
}
