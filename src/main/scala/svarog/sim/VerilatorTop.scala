package svarog.sim

import chisel3._
import svarog.config.CpuSimConfig
import svarog.memory.{L1CacheCpuIO, SimpleMemory}
import svarog.micro.Cpu

/**
 * Thin wrapper that instantiates the CPU together with a fast behavioral memory.
 *
 * Intended for Verilator:
 * - keeps the DataCacheIO interface inside Scala so the generated design is simple
 * - exposes the CPU debug signals so the C++ harness can observe architectural state
 */
class VerilatorTop(cfg: CpuSimConfig) extends Module {
  val io = IO(new Bundle {
    val icache = Flipped(new L1CacheCpuIO(cfg.xlen, 32))

    val debug_regWrite = Output(Bool())
    val debug_writeAddr = Output(UInt(5.W))
    val debug_writeData = Output(UInt(cfg.xlen.W))
    val debug_pc = Output(UInt(cfg.xlen.W))
  })

  private val cpu = Module(new Cpu(cfg.xlen))
  private val dmem = Module(new SimpleMemory(cfg.xlen, cfg.dataMemBytes, cfg.dataInitFile))

  cpu.io.dcache <> dmem.io
  cpu.io.icache <> io.icache

  io.debug_regWrite := cpu.io.debug_regWrite
  io.debug_writeAddr := cpu.io.debug_writeAddr
  io.debug_writeData := cpu.io.debug_writeData
  io.debug_pc := cpu.io.debug_pc
}
