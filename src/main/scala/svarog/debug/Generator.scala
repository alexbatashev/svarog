package svarog.debug

import chisel3._
import chisel3.util._

import svarog.config.SoC
import svarog.memory.WishboneMaster
import svarog.memory.MemWishboneHost

// TODO return either JTAG or Simulator interface
object DebugIOGenerator {
  def apply(config: SoC): Option[ChipDebugSimulatorIO] = {
    if (config.simulatorDebug) {
      Some(
        new ChipDebugSimulatorIO(config.getNumHarts, config.getMaxWordLen)
      )
    } else None
  }
}

object DebugGenerator {
  def apply(
      debugIo: Option[ChipDebugSimulatorIO],
      debug: ChipDebugModule,
      config: SoC
  ): Seq[WishboneMaster] = {
    if (debugIo.nonEmpty) {
      val io = debugIo.get
      val debugDataMaster = Module(
        new MemWishboneHost(config.getMaxWordLen, config.getMaxWordLen)
      )
      val debugInstMaster = Module(
        new MemWishboneHost(config.getMaxWordLen, config.getMaxWordLen)
      )

      debug.io.dmem_iface <> debugDataMaster.mem
      debug.io.imem_iface <> debugInstMaster.mem

      debug.io.hart_in <> io.hart_in
      debug.io.mem_in <> io.mem_in

      io.mem_res <> debug.io.mem_res
      io.reg_res <> debug.io.reg_res
      // FIXME select from correct hart?
      io.halted := debug.io.halted(0)

      Seq(debugInstMaster, debugDataMaster)
    } else {
      debug.io.mem_res.ready := false.B
      debug.io.reg_res.ready := false.B
      debug.io.hart_in.id.valid := false.B
      debug.io.hart_in.id.bits := DontCare
      debug.io.hart_in.bits := DontCare
      debug.io.mem_in.valid := false.B
      debug.io.mem_in.bits := DontCare
      debug.io.imem_iface.req.ready := false.B
      debug.io.imem_iface.resp.valid := false.B
      debug.io.imem_iface.resp.bits := DontCare
      debug.io.dmem_iface.req.ready := false.B
      debug.io.dmem_iface.resp.valid := false.B
      debug.io.dmem_iface.resp.bits := DontCare
      Seq.empty
    }
  }
}
