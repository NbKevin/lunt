package e.lent.util

import java.io.OutputStream
  import scala.util.Random

  object Generate {
    def makeRandomString (length: Int): String = {
      val foo = new StringBuilder
      (1 to length).foreach {
        _ =>
          foo.append ((Random.nextInt (26) + 97).toChar)
      }
      foo.toString
    }

    def generateMockGeoData (howMany: Int, toWhere: OutputStream): Unit = {
      (1 to howMany).foreach {
        _ =>
          val lng = Random.nextFloat () * 360
          val lat = Random.nextFloat () * 180
          val time = System.currentTimeMillis ()
          toWhere.write (s"$lng $lat $time".getBytes ("US-ASCII"))
          toWhere.write ('\n')
      }
    }
}
