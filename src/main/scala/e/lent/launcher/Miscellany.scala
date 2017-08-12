package e.lent.launcher

import e.lent.util.Generate.generateMockGeoData

import java.io.FileOutputStream

object Miscellany extends App {
  val outFile = new java.io.File ("Y:/mock.data")
  generateMockGeoData (10000, new FileOutputStream (outFile))
}

