package svarog.micro

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.tilelink.{
  TLClientNode,
  TLMasterParameters,
  TLMasterPortParameters
}
import svarog.config.Cluster
import svarog.debug.HartDebugIO
import svarog.memory.MemoryIOTileLinkBundleAdapter

class MicroTile(
    val hartBase: Int,
    val cluster: Cluster,
    val startAddress: Long,
    val instSourceIds: Seq[IdRange],
    val dataSourceIds: Seq[IdRange]
)(override implicit val p: Parameters)
    extends LazyModule {

  private val beatBytes = cluster.isa.xlen / 8
  private val xlen = cluster.isa.xlen
  private val numCores = cluster.numCores

  private def clientParams(name: String, id: IdRange) =
    TLMasterParameters.v1(
      name = name,
      sourceId = id,
      supportsProbe = TransferSizes(1, beatBytes),
      supportsGet = TransferSizes(1, beatBytes),
      supportsPutFull = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes)
    )

  // TileLink nodes stay in MicroTile for proper diplomatic resolution
  val instNodes = instSourceIds.zipWithIndex.map { case (id, idx) =>
    TLClientNode(
      Seq(TLMasterPortParameters.v1(Seq(clientParams(s"inst_$idx", id))))
    )
  }

  val dataNodes = dataSourceIds.zipWithIndex.map { case (id, idx) =>
    TLClientNode(
      Seq(TLMasterPortParameters.v1(Seq(clientParams(s"data_$idx", id))))
    )
  }

  // Instantiate Cpu LazyModules - CSR subsystem is inside each Cpu
  val cpus = Seq.tabulate(numCores) { i =>
    LazyModule(
      new Cpu(
        hartId = hartBase + i,
        config = cluster,
        startAddress = startAddress
      )
    )
  }

  lazy val module = new MicroTileImp(this)
}

class MicroTileImp(outer: MicroTile) extends LazyModuleImp(outer) {
  private val xlen = outer.cluster.isa.xlen
  private val numCores = outer.cluster.numCores

  val io = IO(new Bundle {
    val debug = Vec(numCores, Flipped(new HartDebugIO(xlen)))
    val debugRegData = Vec(numCores, Valid(UInt(xlen.W)))
    val halt = Output(Vec(numCores, Bool()))
    val timerInterrupt = Input(Vec(numCores, Bool()))
  })

  require(outer.instNodes.length == numCores, "instNodes must match numCores")
  require(outer.dataNodes.length == numCores, "dataNodes must match numCores")

  // Connect each Cpu with TileLink adapters
  outer.cpus.zipWithIndex.foreach { case (cpu, i) =>
    val (instOut, instEdge) = outer.instNodes(i).out(0)
    val (dataOut, dataEdge) = outer.dataNodes(i).out(0)

    // Create TileLink adapters
    val instAdapter = Module(new MemoryIOTileLinkBundleAdapter(instEdge, xlen))
    val dataAdapter = Module(new MemoryIOTileLinkBundleAdapter(dataEdge, xlen))

    // Connect TileLink bundles to adapter
    instOut <> instAdapter.tl
    dataOut <> dataAdapter.tl

    // Connect adapter's MemoryIO to Cpu
    cpu.module.io.instMem <> instAdapter.mem
    cpu.module.io.dataMem <> dataAdapter.mem

    // Connect external IO
    cpu.module.io.debug <> io.debug(i)
    io.debugRegData(i) <> cpu.module.io.debugRegData
    io.halt(i) := cpu.module.io.halt
    cpu.module.io.timerInterrupt := io.timerInterrupt(i)
  }
}
