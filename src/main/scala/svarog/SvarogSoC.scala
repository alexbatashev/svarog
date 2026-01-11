package svarog

import chisel3._
import chisel3.util._

import svarog.micro.{Cpu => MicroCpu}
import svarog.memory._
import svarog.debug.ChipDebugModule
import svarog.debug.ChipHartDebugIO
import svarog.debug.ChipMemoryDebugIO
import svarog.bits.UartWishbone
import svarog.bits.TimerWishbone
import svarog.bits.IOGenerator
import svarog.debug.DebugIOGenerator
import svarog.debug.DebugGenerator
import svarog.config.Config
import svarog.config.SoC
import svarog.config.Micro

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

  private val coreMems = CoreGenerator(config, debug, startAddress)

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

  private val timer: Option[TimerWishbone] = config.timer.map { timerCfg =>
    val t = Module(new TimerWishbone(
      baseAddr = timerCfg.baseAddr,
      addrWidth = config.getMaxWordLen,
      busWidth = config.getMaxWordLen
    ))
    t.timerClock := io.timerClock
    t
  }

  private val timerSlaves: Seq[WishboneSlave] = timer.toSeq

  private val allMasters: Seq[WishboneMaster] = coreMems ++ debugMasters

  private val allSlaves: Seq[WishboneSlave] = rom ++ memories ++ gpioSlaves ++ timerSlaves

  WishboneRouter(allMasters, allSlaves)
}

object CoreGenerator {
  def apply(
      config: SoC,
      debug: ChipDebugModule,
      startAddress: Long
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

            debug.io.harts(hartId) <> cpu.io.debug
            debug.io.cpuRegData <> cpu.io.debugRegData
            debug.io.cpuHalted(hartId) := cpu.io.halt

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
