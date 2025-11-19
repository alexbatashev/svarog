package svarog.soc

import chisel3._
import chisel3.util._

import svarog.micro.Cpu
import svarog.memory.TCM
import svarog.debug.ChipDebugModule
import svarog.debug.ChipHartDebugIO
import svarog.debug.ChipMemoryDebugIO

class SvarogSoC(
    config: SvarogConfig,
    ramInitFile: Option[String] = None
) extends Module {

  val io = IO(new Bundle {
    val debug = new Bundle {
      val hart_in = Flipped(new ChipHartDebugIO(config.xlen))
      val mem_in = Flipped(Decoupled(new ChipMemoryDebugIO(config.xlen)))
      val mem_res = Decoupled(UInt(config.xlen.W))
      val reg_res = Decoupled(UInt(config.xlen.W))
      val halted = Output(Bool()) // CPU halt status
    }
  })

  private val cpu = Module(
    new Cpu(
      config,
      regfileProbeId = Some("verilator"),
      resetVector = config.programEntryPoint
    )
  )

  private val mem = Module(
    new TCM(
      config.xlen,
      config.memSizeBytes,
      baseAddr = config.programEntryPoint,
      numPorts = if (config.enableDebugInterface) 4 else 2
    )
  )

  private val debug =
    if (config.enableDebugInterface)
      Some(Module(new ChipDebugModule(config.xlen, numHarts = 1)))
    else None

  cpu.io.instmem <> mem.io.ports(0)
  cpu.io.datamem <> mem.io.ports(1)

  if (config.enableDebugInterface) {
    debug.get.io.dmem_iface <> mem.io.ports(2)
    debug.get.io.imem_iface <> mem.io.ports(3)
    debug.get.io.harts(0) <> cpu.io.debug
    debug.get.io.cpuRegData <> cpu.io.debugRegData
    debug.get.io.cpuHalted(0) := cpu.io.halt // CPU halt status to debug module
    debug.get.io.hart_in <> io.debug.hart_in
    debug.get.io.mem_in <> io.debug.mem_in
    io.debug.mem_res <> debug.get.io.mem_res
    io.debug.reg_res <> debug.get.io.reg_res
    io.debug.halted := debug.get.io.halted(0) // Debug module reports halt status
  } else {
    // Default debug inputs (no external debugger connected)
    cpu.io.debug.halt.valid := false.B
    cpu.io.debug.halt.bits := false.B
    cpu.io.debug.breakpoint.valid := false.B
    cpu.io.debug.breakpoint.bits := DontCare
    cpu.io.debug.register.valid := false.B
    cpu.io.debug.register.bits := DontCare
    io.debug.halted := cpu.io.halt // Direct connection when no debug module
  }

}
