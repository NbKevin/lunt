/**
  * Launcher a master that works remotely with other peers.
  */

package e.lent.launcher

import akka.actor.ActorSystem
import akka.event.Logging
import com.typesafe.config.ConfigFactory
import e.lent.{Master, MasterMessage}
import e.lent.util.Hash._

import scala.io.{Source, StdIn}

object RemoteMaster extends App {
  // parse config
  val config = ConfigFactory.load ("instances.conf").getConfig ("master").withFallback (ConfigFactory.load ("common.conf"))
  val testData = config.getString("test-data")

  // create system
  val localSystem = ActorSystem ("lentMaster", config)

  // set log level
  val debug = config.getBoolean ("debug")
  localSystem.eventStream.setLogLevel (if (debug) Logging.DebugLevel else Logging.InfoLevel)

  try {
    // instantiate the master and wait for the peer to connect
    val master = localSystem.actorOf (Master.instance, "master")
    println (s"master up and running at <${master.path}>")
    StdIn.readLine ()

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
  } finally {
    localSystem.terminate ()
  }
}
