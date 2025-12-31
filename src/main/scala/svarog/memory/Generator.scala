package svarog.memory

import chisel3._

import svarog.config.Memory
import svarog.config.{TCM => TCMCfg}
import svarog.config.SoC

object MemGenerator {
  def apply(config: SoC): Seq[WishboneSlave] = {
    config.memories.map { mem =>
      mem match {
        case TCMCfg(baseAddr, length) =>
          Module(
            new TCMWishboneAdapter(
              config.getMaxWordLen,
              memSizeBytes = length,
              baseAddr = baseAddr
            )
          )
      }
    }
  }
}
