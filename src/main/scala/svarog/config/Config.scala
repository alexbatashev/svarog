package svarog.config

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
import scala.util.Success
import scala.util.Failure
import java.io.IOException
import scala.util.Try

/** Describes supported RISC-V extension configuration
  *
  * @param xlen
  *   RISC-V XLEN parameter, must be 32 or 64
  * @param mult
  *   Default 'M' extension support
  * @param zmmul
  *   Multiplication only extension support, implied by 'M'
  * @param zicsr
  *   Control and Status Register support, must be always present
  */
case class ISA(
    xlen: Int,
    mult: Boolean,
    zmmul: Boolean,
    zicsr: Boolean
)

object ISA {

  /** Parses RISC-V extension string into ISA object
    *
    * @param isa
    *   a valid RISC-V extension string like rv32im_zicsr
    */
  def parseFromString(isa: String): Try[ISA] = {
    val pattern = """rv(\d+)([a-z_]+)""".r

    isa.toLowerCase match {
      case pattern(xlenStr, extensions) =>
        Try {
          val xlen = xlenStr.toInt

          if (xlen != 32 && xlen != 64) {
            throw new IOException(s"invalid xlen: $xlen (must be 32 or 64)")
          }

          val parts = extensions.split("_")
          val baseExtensions = parts.head.toSet
          val zExtensions = parts.tail.toSet

          if (!baseExtensions.contains('i')) {
            throw new IOException("RISC-V ISA must include base 'i' extension")
          }

          val supportedBase = Set('i', 'm')
          val supportedZ = Set("zmmul", "zicsr")

          val unsupportedBase = baseExtensions -- supportedBase
          val unsupportedZ = zExtensions -- supportedZ

          if (unsupportedBase.nonEmpty || unsupportedZ.nonEmpty) {
            val unsupported = unsupportedBase.map(_.toString) ++ unsupportedZ
            throw new IOException(
              s"unsupported extensions: ${unsupported.mkString(", ")}"
            )
          }

          ISA(
            xlen = xlen,
            mult = baseExtensions.contains('m'),
            zmmul =
              zExtensions.contains("zmmul") || baseExtensions.contains('m'),
            zicsr = zExtensions.contains("zicsr")
          )
        }

      case _ =>
        Failure(new IOException("invalid RISC-V extension string"))
    }
  }
}

trait CoreType
case object Micro extends CoreType
case object Mini extends CoreType

case class Cluster(
    coreType: CoreType,
    isa: ISA,
    numCores: Int
)

trait IO {
  def numPorts: Int
}
case class UART(name: String, baseAddr: Long) extends IO {
  def numPorts: Int = 2
}

trait Memory {
  def getBaseAddress: Long
}

case class TCM(baseAddress: Long, length: Long) extends Memory {
  def getBaseAddress: Long = baseAddress
}

/** SoC configuration loaded from YAML (without runtime flags) */
case class SoCYaml(
    clusters: Seq[Cluster],
    io: Seq[IO],
    memories: Seq[Memory]
)

/** Complete SoC configuration (YAML + runtime flags) */
case class SoC(
    clusters: Seq[Cluster],
    io: Seq[IO],
    memories: Seq[Memory],
    simulatorDebug: Boolean
) {
  def getMaxWordLen: Int = clusters.map(_.isa.xlen).maxOption.getOrElse(0)
  def getNumHarts: Int = clusters.map(_.numCores).sum
}

object SoC {
  /** Create SoC from YAML config and runtime flags */
  def fromYaml(yaml: SoCYaml, simulatorDebug: Boolean): SoC = {
    SoC(
      clusters = yaml.clusters,
      io = yaml.io,
      memories = yaml.memories,
      simulatorDebug = simulatorDebug
    )
  }
}

object Config {
  // Derive decoders for concrete types
  implicit val uartDecoder: Decoder[UART] = deriveDecoder
  implicit val tcmDecoder: Decoder[TCM] = deriveDecoder

  // Polymorphic decoder for IO based on "type" field
  implicit val ioDecoder: Decoder[IO] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "uart" => cursor.as[UART]
      case other =>
        Left(
          io.circe.DecodingFailure(s"unknown IO type: $other", cursor.history)
        )
    }
  }

  // Polymorphic decoder for Memory based on "type" field
  implicit val memoryDecoder: Decoder[Memory] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "tcm" => cursor.as[TCM]
      case other =>
        Left(
          io.circe
            .DecodingFailure(s"unknown Memory type: $other", cursor.history)
        )
    }
  }

  implicit val clusterDecoder: Decoder[Cluster] = deriveDecoder
  implicit val socYamlDecoder: Decoder[SoCYaml] = deriveDecoder

  implicit val coreTypeDecoder: Decoder[CoreType] =
    Decoder.decodeString.emapTry { str =>
      str match {
        case "micro" =>
          Success(Micro)
        case "mini" =>
          Success(Mini)
        case _ => Failure(new IOException("invalid core type"))
      }
    }
  implicit val isaDecoder: Decoder[ISA] = Decoder.decodeString.emapTry { str =>
    ISA.parseFromString(str)
  }
}
