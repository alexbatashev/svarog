package svarog

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink.{TLBuffer, TLFragmenter, TLXbar}
import svarog.config.{Micro, SoC, TCM => TCMCfg}
import svarog.debug.{DebugIOGenerator, TLChipDebugModule}
import svarog.memory.{ROMTileLinkAdapter, TCM}
import svarog.micro.MicroTile
import svarog.bits.{IOGenerator, RTC}
import svarog.interrupt.{MSIP, Timer}

class SvarogSoC(
    config: SoC,
    bootloader: Option[String] = None
)(override implicit val p: Parameters)
    extends LazyModule {

  private val xlen = config.getMaxWordLen
  private val startAddress = bootloader
    .map(_ => 0x00480000L)
    .getOrElse(config.memories.head.getBaseAddress)

  private val xbar = LazyModule(new TLXbar)

  private var nextSourceId = 0
  private def allocSourceId(): IdRange = {
    val id = nextSourceId
    nextSourceId += 1
    IdRange(id, id + 1)
  }

  private val tiles = config.clusters.zipWithIndex.map {
    case (cluster, clusterIdx) if cluster.coreType == Micro =>
      val hartBase = config.clusters.take(clusterIdx).map(_.numCores).sum
      val instIds = Seq.fill(cluster.numCores)(allocSourceId())
      val dataIds = Seq.fill(cluster.numCores)(allocSourceId())
      LazyModule(
        new MicroTile(hartBase, cluster, startAddress, instIds, dataIds)
      )
    case _ =>
      sys.error("Only Micro tiles are supported in the TileLink SoC for now.")
  }

  tiles.foreach { tile =>
    tile.instNodes.foreach { n => xbar.node := n }
    tile.dataNodes.foreach { n => xbar.node := n }
  }

  private val tcm = config.memories.map { case TCMCfg(baseAddr, length) =>
    // FIXME how do I make it dual port?
    val tcm = LazyModule(new TCM(xlen, length, baseAddr, numPorts = 1))
    tcm.node := xbar.node
    tcm
  }

  private val romAdapter = bootloader.map { path =>
    val rom = LazyModule(
      new ROMTileLinkAdapter(xlen, baseAddr = 0x00480000L, file = path)
    )
    rom.node := xbar.node
    rom
  }

  private val uartAdapters = IOGenerator.generateUARTs(config)
  uartAdapters.foreach { uart =>
    uart.node := TLBuffer() := xbar.node
  }

  private val debugModule = if (config.simulatorDebug) {
    val instId = allocSourceId()
    val dataId = allocSourceId()
    val dbg = LazyModule(
      new TLChipDebugModule(xlen, config.getNumHarts, instId, dataId)
    )
    xbar.node := dbg.instNode
    xbar.node := dbg.dataNode
    Some(dbg)
  } else None

  // Timer for machine timer interrupts (mtime/mtimecmp)
  private val timer = LazyModule(
    new Timer(
      numHarts = config.getNumHarts,
      xlen = xlen,
      baseAddr = 0x02000000L
    )
  )
  timer.node := TLFragmenter(xlen / 8, xlen / 8) := xbar.node

  private val msip = LazyModule(
    new MSIP(numHarts = config.getNumHarts, xlen = xlen, baseAddr = 0x02010000L)
  )
  msip.node := TLFragmenter(xlen / 8, xlen / 8) := xbar.node

  lazy val module = new SvarogSoCImp(this)
  class SvarogSoCImp(outer: SvarogSoC) extends LazyModuleImp(outer) {
    val io = IO(new Bundle {
      val gpio = IOGenerator.generatePins(config)
      val debug = DebugIOGenerator(config)
      val rtcClock = Input(Clock())
    })

    // RTC provides mtime value with clock domain crossing
    private val rtc = Module(new RTC)
    rtc.clk := clock
    rtc.reset := reset.asBool
    rtc.io.rtcClock := io.rtcClock

    // Connect RTC time to Timer
    outer.timer.module.io.time := rtc.io.time

    // Flatten debug signals from all tiles
    val allDebugPorts = tiles.flatMap(_.module.io.debug)
    val allRegData = tiles.flatMap(_.module.io.debugRegData)
    val allHalted = tiles.flatMap(_.module.io.halt)

    outer.debugModule match {
      case Some(debugLazy) =>
        val dbg = debugLazy.module

        // Connect external debug IO
        io.debug.get <> dbg.debug

        // Connect to all harts
        allDebugPorts.zipWithIndex.foreach { case (port, i) =>
          port <> dbg.harts(i)
        }

        // Connect register data (use first hart's data for now)
        dbg.cpuRegData := allRegData.headOption.getOrElse {
          val w = Wire(Valid(UInt(xlen.W)))
          w.valid := false.B
          w.bits := 0.U
          w
        }

        // Connect halt status
        allHalted.zipWithIndex.foreach { case (halt, i) =>
          dbg.cpuHalted(i) := halt
        }

      case None =>
        // No debug module - tie off debug ports
        allDebugPorts.foreach { d =>
          d.halt.valid := false.B
          d.halt.bits := false.B
          d.breakpoint.valid := false.B
          d.breakpoint.bits.pc := 0.U
          d.watchpoint.valid := false.B
          d.watchpoint.bits.addr := 0.U
          d.register.valid := false.B
          d.register.bits.reg := 0.U
          d.register.bits.write := false.B
          d.register.bits.data := 0.U
          d.setPC.valid := false.B
          d.setPC.bits.pc := 0.U
        }
    }

    IOGenerator.connectPins(outer.uartAdapters, io.gpio)

    // Route timer and software interrupts to tiles
    var hartIdx = 0
    tiles.foreach { tile =>
      val numCores = tile.module.io.timerInterrupt.length
      for (i <- 0 until numCores) {
        tile.module.io.timerInterrupt(i) := outer.timer.module.io.fire(hartIdx)
        tile.module.io.softwareInterrupt(i) := outer.msip.module.io
          .fire(hartIdx)
        hartIdx += 1
      }
    }
  }
}
