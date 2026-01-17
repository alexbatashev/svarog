package svarog.bits

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule

import svarog.config.SoC
import svarog.config.{UART => UartCfg}

object IOGenerator {
  def generatePins(config: SoC): Vec[Pin] = {
    val totalNumPorts = config.io.map(_.numPorts).sum
    Vec(totalNumPorts, new Pin())
  }

  /** Create UART LazyModules. Must be called from outer LazyModule scope. */
  def generateUARTs(config: SoC)(implicit p: Parameters): Seq[TLUART] = {
    config.io.collect { case UartCfg(name, baseAddr) =>
      LazyModule(new TLUART(baseAddr))
    }
  }

  /** Connect UART pins. Must be called from LazyModuleImp. */
  def connectPins(uarts: Seq[TLUART], pins: Vec[Pin]): Unit = {
    var pinOffset = 0
    uarts.foreach { uartLazy =>
      val uart = uartLazy.module

      // RX pin
      uart.uart.rxd := pins(pinOffset).input
      pins(pinOffset).write := false.B
      pins(pinOffset).output := false.B

      // TX pin
      pins(pinOffset + 1).output := uart.uart.txd
      pins(pinOffset + 1).input := DontCare
      pins(pinOffset + 1).write := true.B

      pinOffset += 2
    }
  }
}
