package svarog

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink.TLXbar
import svarog.config.{Micro, SoC, TCM => TCMCfg}
import svarog.debug.HartDebugIO
import svarog.memory.{ROMTileLinkAdapter, TCMTileLinkAdapter}
import svarog.micro.MicroTile

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
      LazyModule(new MicroTile(hartBase, cluster, startAddress, instIds, dataIds))
    case _ =>
      sys.error("Only Micro tiles are supported in the TileLink SoC for now.")
  }

  tiles.foreach { tile =>
    tile.instNodes.foreach { n => xbar.node := n }
    tile.dataNodes.foreach { n => xbar.node := n }
  }

  private val tcmAdapters = config.memories.map {
    case TCMCfg(baseAddr, length) =>
      val tcm = LazyModule(new TCMTileLinkAdapter(xlen, length, baseAddr))
      tcm.node := xbar.node
      tcm
  }

  private val romAdapter = bootloader.map { path =>
    val rom = LazyModule(new ROMTileLinkAdapter(xlen, baseAddr = 0x00480000L, file = path))
    rom.node := xbar.node
    rom
  }

  lazy val module = new SvarogSoCImp(this)
  class SvarogSoCImp(outer: SvarogSoC) extends LazyModuleImp(outer) {
    val io = IO(new Bundle {})

    tiles.foreach { tile =>
      val t = tile.module
      t.io.debug.foreach { d =>
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
  }
}
