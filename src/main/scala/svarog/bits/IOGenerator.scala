package svarog.bits

import chisel3._
import chisel3.experimental.{Analog, attach}

import svarog.config.SoC
import svarog.config.{UART => UartCfg}
import svarog.bits.UartMemory
import svarog.memory.WishboneSlave

object IOGenerator {
  def generatePins(config: SoC): Vec[Pin] = {
    val totalNumPorts = config.io.map(_.numPorts).sum
    Vec(totalNumPorts, new Pin())
  }

  def generateSocIo(config: SoC, pins: Vec[Pin]): Seq[WishboneSlave] = {
    var pinOffset = 0

    config.io.map { io =>
      val currentOffset = pinOffset
      pinOffset += io.numPorts

      io match {
        case UartCfg(name, baseAddr) => {
          val uart = Module(
            new UartMemory(
              baseAddr,
              addrWidth = config.getMaxWordLen,
              dataWidth = config.getMaxWordLen
            )
          )

          uart.uart.rxd := pins(pinOffset - 2).input
          pins(pinOffset - 2).write := false.B
          pins(pinOffset - 2).output := false.B

          pins(pinOffset - 1).output := uart.uart.txd
          pins(pinOffset - 1).input := DontCare
          pins(pinOffset - 1).write := true.B

          uart
        }
      }
    }.toSeq
  }
}
