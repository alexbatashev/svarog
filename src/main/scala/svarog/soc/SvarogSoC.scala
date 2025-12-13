package svarog.soc

import chisel3._
import chisel3.util._

import svarog.micro.Cpu
import svarog.memory._
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

  // ============================================================================
  // CPU
  // ============================================================================

  private val cpu = Module(
    new Cpu(
      config,
      regfileProbeId = Some("verilator"),
      resetVector = config.programEntryPoint
    )
  )

  // ============================================================================
  // Wishbone Masters
  // ============================================================================

  // CPU instruction fetch master
  private val instMaster = Module(new MemoryToWishbone(config.xlen))
  instMaster.memIO <> cpu.io.instmem

  // CPU data memory master
  private val dataMaster = Module(new MemoryToWishbone(config.xlen))
  dataMaster.memIO <> cpu.io.datamem

  // Debug interface masters (if enabled)
  private val debugInstMaster = if (config.enableDebugInterface) {
    Some(Module(new MemoryToWishbone(config.xlen)))
  } else None

  private val debugDataMaster = if (config.enableDebugInterface) {
    Some(Module(new MemoryToWishbone(config.xlen)))
  } else None

  // ============================================================================
  // Wishbone Slaves
  // ============================================================================

  // TCM wrapped as Wishbone slave
  private val tcmSlave = Module(
    new WishboneTCM(
      xlen = config.xlen,
      memSizeBytes = config.memSizeBytes,
      baseAddr = config.programEntryPoint
    )
  )

  // ============================================================================
  // Wishbone Router
  // ============================================================================

  // Collect all masters and slaves
  private val allMasters: Seq[WishboneMaster] = Seq(instMaster, dataMaster) ++
    (if (config.enableDebugInterface) Seq(debugInstMaster.get, debugDataMaster.get) else Seq.empty)

  private val allSlaves: Seq[WishboneSlave] = Seq(tcmSlave)

  // Connect them using the typesafe WishboneRouter
  WishboneRouter(allMasters, allSlaves)

  // ============================================================================
  // Debug Interface
  // ============================================================================

  private val debug =
    if (config.enableDebugInterface)
      Some(Module(new ChipDebugModule(config.xlen, numHarts = 1)))
    else None

  if (config.enableDebugInterface) {
    // Connect debug module memory interfaces to Wishbone masters
    debug.get.io.dmem_iface <> debugDataMaster.get.memIO
    debug.get.io.imem_iface <> debugInstMaster.get.memIO

    // Connect debug control interfaces
    debug.get.io.harts(0) <> cpu.io.debug
    debug.get.io.cpuRegData <> cpu.io.debugRegData
    debug.get.io.cpuHalted(0) := cpu.io.halt
    debug.get.io.hart_in <> io.debug.hart_in
    debug.get.io.mem_in <> io.debug.mem_in
    io.debug.mem_res <> debug.get.io.mem_res
    io.debug.reg_res <> debug.get.io.reg_res
    io.debug.halted := debug.get.io.halted(0)
  } else {
    // Default debug inputs (no external debugger connected)
    cpu.io.debug.halt.valid := false.B
    cpu.io.debug.halt.bits := false.B
    cpu.io.debug.breakpoint.valid := false.B
    cpu.io.debug.breakpoint.bits := DontCare
    cpu.io.debug.register.valid := false.B
    cpu.io.debug.register.bits := DontCare
    io.debug.halted := cpu.io.halt
  }
}
