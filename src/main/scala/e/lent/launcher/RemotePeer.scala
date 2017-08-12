/**
  * Launcher a peer that works remotely with other peers and/or a master.
  */

package e.lent.launcher

import akka.actor.ActorSystem
import akka.event.Logging
import com.typesafe.config.ConfigFactory

import e.lent.{Peer, PeerMessage}
import e.lent.util.Generate._
import e.lent.util.Hash.hash

import scala.io.StdIn

object RemotePeer extends App {
  // parse config
  val config = ConfigFactory.load ("instances.conf").getConfig ("peer").withFallback (ConfigFactory.load ("common.conf"))
  val m = 1

  // assemble master info
  val masterInfo = config.getConfig ("master")
  val masterPort = ConfigFactory.load ("instances.conf").getString ("master.akka.remote.netty.tcp.port")
  val masterHost = masterInfo.getString ("host")
  val masterName = masterInfo.getString ("name")
  val masterSysName = masterInfo.getString ("system-name")
  val masterPath = s"akka.tcp://$masterSysName@$masterHost:$masterPort/user/$masterName"

  // create system
  val localSystem = ActorSystem ("lentMaster", config)

  // set log level
  val debug = config.getBoolean ("debug")
  localSystem.eventStream.setLogLevel (if (debug) Logging.DebugLevel else Logging.InfoLevel)

  try {
    val peerName = hash (makeRandomString (12))
    val peer = localSystem.actorOf (Peer.makeNew (peerName, null), peerName)
    peer ! PeerMessage.ConnectToMaster (masterPath)
    println (">>> Press ENTER to exit <<<")
    StdIn.readLine ()
  } finally {
    localSystem.terminate ()
  }
}
