package svarog

import chisel3._
import chisel3.util._

import svarog.micro.Cpu
import svarog.memory._
import svarog.debug.ChipDebugModule
import svarog.debug.ChipHartDebugIO
import svarog.debug.ChipMemoryDebugIO
import svarog.debug.UartWishbone

class SvarogSoC(
    config: SvarogConfig
) extends Module {

  val io = IO(new Bundle {
    val debug = new Bundle {
      val hart_in = Flipped(new ChipHartDebugIO(config.cores.head.xlen))
      val mem_in = Flipped(Decoupled(new ChipMemoryDebugIO(config.cores.head.xlen)))
      val mem_res = Decoupled(UInt(config.cores.head.xlen.W))
      val reg_res = Decoupled(UInt(config.cores.head.xlen.W))
      val halted = Output(Bool()) // CPU halt status
    }

    val uarts = Vec(config.soc.uarts.filter(_.enabled).length, new Bundle {
      val txd = Output(Bool())
      val rxd = Input(Bool())
    })
  })

  private val debug =
    if (config.soc.enableDebug)
      Some(Module(new ChipDebugModule(config.cores.head.xlen, numHarts = config.cores.length)))
    else None

  private val debugMasters = if (config.soc.enableDebug) {
    val debugDataMaster = Module(new MemWishboneHost(config.cores.head.xlen, config.cores.head.xlen))
    val debugInstMaster = Module(new MemWishboneHost(config.cores.head.xlen, config.cores.head.xlen))

    // Connect debug module memory interfaces to Wishbone masters
    debug.get.io.dmem_iface <> debugDataMaster.mem
    debug.get.io.imem_iface <> debugInstMaster.mem

    debug.get.io.hart_in <> io.debug.hart_in
    debug.get.io.mem_in <> io.debug.mem_in
    io.debug.mem_res <> debug.get.io.mem_res
    io.debug.reg_res <> debug.get.io.reg_res
    io.debug.halted := debug.get.io.halted(0)

    Seq(debugDataMaster, debugInstMaster)
  } else Seq.empty

  private val coreMem = config.cores.zipWithIndex.map { case(core, i) =>
    val cpu = Module(new Cpu(core.micro.get, config.memory.head.tcm.startAddress))

    if (config.soc.enableDebug) {
      // Connect debug control interfaces
      debug.get.io.harts(0) <> cpu.io.debug
      debug.get.io.cpuRegData <> cpu.io.debugRegData
      debug.get.io.cpuHalted(0) := cpu.io.halt
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

    val cpuInstHost = Module(
      new MemWishboneHost(core.xlen, core.xlen)
    )
    val cpuDataHost = Module(
      new MemWishboneHost(core.xlen, core.xlen)
    )
    cpu.io.instmem <> cpuInstHost.mem
    cpu.io.datamem <> cpuDataHost.mem

    List(cpuInstHost, cpuDataHost)
  }.flatten

  private val memories = config.memory.map{mem =>
    val tcm = Module(
      new TCMWishboneAdapter(
        xlen = config.cores.head.xlen,
        memSizeBytes = mem.tcm.size,
        baseAddr = mem.tcm.startAddress,
      )
    )

    tcm
  }

  // Instantiate UART modules
  private val uartModules = config.soc.uarts.filter(_.enabled).map { uartConfig =>
    Module(new UartWishbone(
      baseAddr = uartConfig.baseAddr,
      dataWidth = 8,
      addrWidth = config.cores.head.xlen,
      busWidth = config.cores.head.xlen
    ))
  }

  // Connect UART IO
  uartModules.zipWithIndex.foreach { case (uart, idx) =>
    io.uarts(idx).txd := uart.uart.txd
    uart.uart.rxd := io.uarts(idx).rxd
  }

  private val allMasters: Seq[WishboneMaster] = coreMem ++ debugMasters

  private val allSlaves: Seq[WishboneSlave] = memories ++ uartModules

  WishboneRouter(allMasters, allSlaves)
}
