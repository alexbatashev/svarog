package svarog

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

case class SoCConfig(
    enableDebug: Boolean = true,
    uarts: List[UartConfig] = List.empty
)

case class UartConfig(
    name: String,
    baseAddr: Long,
    enabled: Boolean = true
)

case class MicroCoreConfig(xlen: Int)

case class CoreEntry(
    micro: Option[MicroCoreConfig] = None
) {
  def coreType: String = {
    if (micro.isDefined) "micro"
    else "unknown"
  }

  def xlen: Int = {
    micro
      .map(_.xlen)
      .getOrElse(
        throw new IllegalStateException("No core type defined")
      )
  }
}

case class TcmMemoryConfig(
    size: Int,
    startAddress: Long
)

case class MemoryEntry(tcm: TcmMemoryConfig)

case class SvarogConfig(
    soc: SoCConfig,
    cores: List[CoreEntry],
    memory: List[MemoryEntry],
    bootloader: Option[String] = None
) {
  def validate(): Either[String, Unit] = {
    if (cores.isEmpty) return Left("No cores defined")

    // Validate each core entry has exactly one core type defined
    cores.zipWithIndex.foreach { case (core, idx) =>
      val definedTypes = List(
        core.micro.isDefined
      ).count(identity)

      if (definedTypes == 0)
        return Left(s"Core entry $idx has no core type defined")
      if (definedTypes > 1)
        return Left(s"Core entry $idx has multiple core types defined")
    }

    // Micro core type requires exactly one core
    val microCores = cores.filter(_.coreType == "micro")
    if (microCores.nonEmpty && cores.size != 1) {
      return Left(
        "Micro core type requires exactly one core, got " + cores.size
      )
    }

    if (memory.isEmpty) return Left("No memory defined")
    if (memory.size > 1) return Left("Only single memory block supported")

    val xlen = cores.head.xlen
    if (xlen != 32) return Left(s"Only xlen=32 supported, got xlen=$xlen")

    val memSize = memory.head.tcm.size
    if (memSize <= 0) return Left(s"Memory size must be > 0, got $memSize")

    // Validate UART configurations
    soc.uarts.zipWithIndex.foreach { case (uart, idx) =>
      if (uart.baseAddr < 0)
        return Left(s"UART '${uart.name}' base address must be non-negative")

      // Check for duplicate names
      if (soc.uarts.count(_.name == uart.name) > 1)
        return Left(s"Duplicate UART name: '${uart.name}'")

      // Check for address overlaps with other UARTs
      soc.uarts.zipWithIndex.foreach { case (other, otherIdx) =>
        if (idx != otherIdx && uart.enabled && other.enabled) {
          val uartEnd = uart.baseAddr + 0x10
          val otherEnd = other.baseAddr + 0x10
          if (uart.baseAddr < otherEnd && uartEnd > other.baseAddr) {
            return Left(
              s"UART '${uart.name}' address range overlaps with '${other.name}'"
            )
          }
        }
      }
    }

    Right(())
  }
}

object SvarogConfig {
  implicit val uartConfigDecoder: Decoder[UartConfig] = deriveDecoder
  implicit val microCoreConfigDecoder: Decoder[MicroCoreConfig] = deriveDecoder
  implicit val coreEntryDecoder: Decoder[CoreEntry] = deriveDecoder
  implicit val tcmMemoryConfigDecoder: Decoder[TcmMemoryConfig] = deriveDecoder
  implicit val memoryEntryDecoder: Decoder[MemoryEntry] = deriveDecoder
  implicit val socConfigDecoder: Decoder[SoCConfig] = deriveDecoder
  implicit val svarogConfigDecoder: Decoder[SvarogConfig] = deriveDecoder
}
