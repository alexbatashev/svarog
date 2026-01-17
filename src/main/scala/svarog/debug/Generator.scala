package svarog.debug

import chisel3._

import svarog.config.SoC

object DebugIOGenerator {
  def apply(config: SoC): Option[ChipDebugSimulatorIO] = {
    if (config.simulatorDebug) {
      Some(new ChipDebugSimulatorIO(config.getNumHarts, config.getMaxWordLen))
    } else None
  }
}
