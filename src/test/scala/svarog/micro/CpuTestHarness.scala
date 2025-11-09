package svarog.micro

import chisel3._
import chisel3.util.experimental.BoringUtils
import svarog.memory.{DataCacheIO, L1CacheCpuIO}

object CpuTestHarness {
  val DebugTapId = "cpu_test_debug"
}

class CpuTestHarness(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val icache = Flipped(new L1CacheCpuIO(xlen, 32))
    val dcache = new DataCacheIO(xlen)

    val debug_regWrite = Output(Bool())
    val debug_writeAddr = Output(UInt(5.W))
    val debug_writeData = Output(UInt(xlen.W))
    val debug_pc = Output(UInt(xlen.W))

  })

  private val cpu = Module(
    new Cpu(
      xlen,
      debugTapId = Some(CpuTestHarness.DebugTapId)
    )
  )
  cpu.io.icache <> io.icache
  cpu.io.dcache <> io.dcache
  cpu.io.bootHold := false.B

  val debugRegWrite = Wire(Bool())
  val debugWriteAddr = Wire(UInt(5.W))
  val debugWriteData = Wire(UInt(xlen.W))
  val debugPc = Wire(UInt(xlen.W))

  BoringUtils.addSink(debugRegWrite, CpuDebugTap.regWrite(CpuTestHarness.DebugTapId))
  BoringUtils.addSink(debugWriteAddr, CpuDebugTap.writeAddr(CpuTestHarness.DebugTapId))
  BoringUtils.addSink(debugWriteData, CpuDebugTap.writeData(CpuTestHarness.DebugTapId))
  BoringUtils.addSink(debugPc, CpuDebugTap.pc(CpuTestHarness.DebugTapId))

  io.debug_regWrite := debugRegWrite
  io.debug_writeAddr := debugWriteAddr
  io.debug_writeData := debugWriteData
  io.debug_pc := debugPc

}
