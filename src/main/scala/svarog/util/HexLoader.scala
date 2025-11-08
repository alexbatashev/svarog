package svarog.util

import scala.io.Source
import scala.util.Using

object HexLoader {
  /** Parse a Verilog-style hex file (`@addr` directives + words). */
  def loadWords(path: String): Seq[BigInt] = {
    Using.resource(Source.fromFile(path)) { src =>
      src.getLines().flatMap { line =>
        val trimmed = line.trim
        if (trimmed.isEmpty || trimmed.startsWith("@")) Nil
        else trimmed.split("\\s+").filter(_.nonEmpty)
      }.map(token => BigInt(token, 16)).toSeq
    }
  }
}
