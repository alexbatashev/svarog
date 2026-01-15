package svarog

import chisel3._
import chisel3.util._

import svarog.micro.{Cpu => MicroCpu}
import svarog.memory._
import svarog.debug.ChipDebugModule
import svarog.debug.ChipHartDebugIO
import svarog.debug.ChipMemoryDebugIO
import svarog.bits.UartWishbone
import svarog.bits.RTC
import svarog.bits.IOGenerator
import svarog.debug.DebugIOGenerator
import svarog.debug.DebugGenerator
import svarog.config.Config
import svarog.config.SoC
import svarog.config.Micro
import svarog.interrupt.{Timer, TimerWishbone, MSWI}

class SvarogSoC(
    config: SoC,
    bootloader: Option[String] = None
) extends Module {

  val io = IO(new Bundle {
    val debug = DebugIOGenerator(config)
    val gpio = IOGenerator.generatePins(config)
    val timerClock = Input(Clock())  // Timer clock input (can be tied to system clock)
  })

  private val startAddress = bootloader
    .map(_ => 0x00480000L)
    .getOrElse(config.memories.head.getBaseAddress)

  private val debug = Module(
    new ChipDebugModule(
      config.getMaxWordLen,
      numHarts = config.getNumHarts
    )
  )

  private val debugMasters = DebugGenerator(io.debug, debug, config)

  // ============================================================================
  // Interrupt Infrastructure (ACLINT-style)
  // ============================================================================

  private val numHarts = config.getNumHarts
  private val xlen = config.getMaxWordLen

  // RTC: Real-time counter (clock domain crossing from timerClock to system clock)
  private val rtc = Module(new RTC)
  rtc.clk := clock
  rtc.reset := reset
  rtc.io.rtcClock := io.timerClock

  // Timer and MSWI (optional, based on config)
  private val timerAndMswi: Option[(Timer, TimerWishbone, MSWI)] = config.timer.map { timerCfg =>
    // Timer: Compares mtime with mtimecmp, fires interrupts
    val timer = Module(new Timer(numHarts))
    timer.io.time := rtc.io.time

    // TimerWishbone: Memory-mapped access to mtime/mtimecmp
    // Layout: mtimecmp[0..numHarts-1] at baseAddr, mtime at baseAddr + numHarts*8
    val timerWb = TimerWishbone(timer, xlen, xlen, timerCfg.baseAddr)

    // MSWI: Memory-mapped software interrupt registers
    // Standard CLINT places MSWI 0x4000 bytes before MTIMER
    val mswiBaseAddr = timerCfg.baseAddr - 0x4000
    val mswi = MSWI(numHarts, xlen, xlen, mswiBaseAddr)

    (timer, timerWb, mswi)
  }

  // Extract timer fire signals and MSWI signals for CoreGenerator
  private val timerFire: Vec[Bool] = timerAndMswi.map(_._1.io.control.fire).getOrElse(
    WireDefault(VecInit(Seq.fill(numHarts)(false.B)))
  )
  private val msipSignals: Vec[Bool] = timerAndMswi.map(_._3.msip).getOrElse(
    WireDefault(VecInit(Seq.fill(numHarts)(false.B)))
  )

  // ============================================================================
  // Core Generation
  // ============================================================================

  private val coreMems = CoreGenerator(config, debug, startAddress, timerFire, msipSignals)

  private val rom: Seq[WishboneSlave] = bootloader.map { bootloader =>
    Module(
      new ROMWishboneAdapter(
        config.getMaxWordLen,
        baseAddr = 0x00480000,
        bootloader
      )
    )
  }.toSeq

  private val memories = MemGenerator(config)

  private val gpioSlaves = IOGenerator.generateSocIo(config, io.gpio)

  // Collect interrupt-related Wishbone slaves
  private val interruptSlaves: Seq[WishboneSlave] = timerAndMswi.map { case (_, timerWb, mswi) =>
    Seq(timerWb, mswi)
  }.getOrElse(Seq.empty)

  private val allMasters: Seq[WishboneMaster] = coreMems ++ debugMasters

  private val allSlaves: Seq[WishboneSlave] = rom ++ memories ++ gpioSlaves ++ interruptSlaves

  WishboneRouter(allMasters, allSlaves)
}

object CoreGenerator {
  def apply(
      config: SoC,
      debug: ChipDebugModule,
      startAddress: Long,
      timerFire: Vec[Bool],
      msipSignals: Vec[Bool]
  ): Seq[WishboneMaster] = {
    config.clusters.zipWithIndex.flatMap { case (cluster, clusterIdx) =>
      val clusterStartHartId =
        config.clusters.take(clusterIdx).map(_.numCores).sum

      (0 until cluster.numCores).map { coreIdx =>
        val hartId = clusterStartHartId + coreIdx

        cluster.coreType match {
          case Micro => {
            val cpu =
              Module(new MicroCpu(hartId, cluster, startAddress = startAddress))

            // Debug connections
            debug.io.harts(hartId) <> cpu.io.debug
            debug.io.cpuRegData <> cpu.io.debugRegData
            debug.io.cpuHalted(hartId) := cpu.io.halt

            // Interrupt connections
            cpu.io.mtip := timerFire(hartId)     // Machine timer interrupt
            cpu.io.msip := msipSignals(hartId)  // Machine software interrupt
            cpu.io.meip := false.B              // Machine external interrupt (not connected yet)

            // Memory interfaces
            val cpuInstHost = Module(
              new MemWishboneHost(cluster.isa.xlen, cluster.isa.xlen)
            )
            val cpuDataHost = Module(
              new MemWishboneHost(cluster.isa.xlen, cluster.isa.xlen)
            )
            cpu.io.instmem <> cpuInstHost.mem
            cpu.io.datamem <> cpuDataHost.mem

            List(cpuInstHost, cpuDataHost)
          }
        }
      }
    }.flatten
  }
}
