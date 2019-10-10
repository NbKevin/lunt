package e.lent.util

import akka.actor.{ActorContext, ActorRef, ExtendedActorSystem}
import akka.serialization.Serialization

import scala.language.implicitConversions

object SerialisationWrapper {
  implicit class ActorRefExtended (val actorRef: ActorRef) extends AnyVal {
    /**
      * Serialise an actor reference.
      *
      * @return serialised string form
      */
    def serialise: String = Serialization.serializedActorPath (actorRef)
  }

  /**
    * Deserialise an actor reference from its serialised form (full path).
    *
    * @param path    serialised form, full path
    * @param context actor context
    * @return corresponding actor reference
    */
  private def deserialiseActorRef (path: String, context: ActorContext): ActorRef =
    context.system.asInstanceOf [ExtendedActorSystem].provider.resolveActorRef (path)


  implicit class PseudoOutsourceRoutingEntryExtended
  (val entry: e.lent.message.peer.OutsourceRoutingEntry) extends AnyVal {
    /**
      * Deserialise an outsource routing entry.
      *
      * @param context context in which the deserialisation takes place
      * @return deserialised outsource routing entry
      */
    def deserialise (context: ActorContext): e.lent.OutsourceRoutingEntry =
      new e.lent.OutsourceRoutingEntry (deserialiseActorRef (entry.actor, context), entry.time)
  }


  implicit class OutsourceRoutingEntryExtended (val entry: e.lent.OutsourceRoutingEntry) extends AnyVal {
    /**
      * Serialise an outsource routing entry.
      *
      * @return Serialised outsource routing entry.
      */
    def serialise: e.lent.message.peer.OutsourceRoutingEntry =
      e.lent.message.peer.OutsourceRoutingEntry (entry.time, entry.actor.serialise)
  }


  implicit class SerialisedActorRefExtended (val serialisedActorRef: String) extends AnyVal {
    /**
      * Deserialise an actor reference within a given context.
      *
      * @param context context
      * @return actor reference
      */
    def deserialiseWithContext (context: ActorContext): ActorRef =
      deserialiseActorRef (serialisedActorRef, context)
  }


  implicit class StringExtended (val string: String) extends AnyVal {
    def toByteString: com.google.protobuf.ByteString =
      com.google.protobuf.ByteString.copyFrom (string, java.nio.charset.Charset.defaultCharset)
  }
}
