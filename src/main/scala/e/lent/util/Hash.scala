package e.lent.util

import java.security.MessageDigest

object Hash {
  private val makeHashFunc: PartialFunction[String, (Any) => String] = {
    case hashAlgorithm => something =>
      val stringRep = something.toString
      val rawSha1 = MessageDigest.getInstance (hashAlgorithm).digest (stringRep.getBytes)
      rawSha1.map ("%02x".format (_)).mkString
  }

  val defaultHashAlgorithm = "SHA1"
  val defaultHashLengthInBytes = 32

  def hash (something: Any): String = makeHashFunc(defaultHashAlgorithm)(something).take(defaultHashLengthInBytes)
}
