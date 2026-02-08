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

  // Instantiate Cpu LazyModules - CSR subsystem and Fetch TL node are inside each Cpu
  val cpus = Seq.tabulate(numCores) { i =>
    LazyModule(
      new Cpu(
        hartId = hartBase + i,
        config = cluster,
        startAddress = startAddress
      )
    )
  }

  // Instruction TileLink nodes come from Fetch inside each Cpu (native TL)
  val instNodes = cpus.map(_.fetch.node)

  val dataNodes = dataSourceIds.zipWithIndex.map { case (id, idx) =>
    TLClientNode(
      Seq(TLMasterPortParameters.v1(Seq(clientParams(s"data_$idx", id))))
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
    val softwareInterrupt = Input(Vec(numCores, Bool()))
  })

  require(outer.instNodes.length == numCores, "instNodes must match numCores")
  require(outer.dataNodes.length == numCores, "dataNodes must match numCores")

  // Connect each Cpu with TileLink adapters (data only; fetch uses native TL)
  outer.cpus.zipWithIndex.foreach { case (cpu, i) =>
    val (dataOut, dataEdge) = outer.dataNodes(i).out(0)

    val dataAdapter = Module(new MemoryIOTileLinkBundleAdapter(dataEdge, xlen))
    dataOut <> dataAdapter.tl
    cpu.module.io.dataMem <> dataAdapter.mem

    // Connect external IO
    cpu.module.io.debug <> io.debug(i)
    io.debugRegData(i) <> cpu.module.io.debugRegData
    io.halt(i) := cpu.module.io.halt
    cpu.module.io.timerInterrupt := io.timerInterrupt(i)
    cpu.module.io.softwareInterrupt := io.softwareInterrupt(i)
  }
}
