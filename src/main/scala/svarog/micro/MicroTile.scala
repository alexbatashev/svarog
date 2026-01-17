package svarog.micro

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.tilelink.{
  TLBundle,
  TLClientNode,
  TLMasterParameters,
  TLMasterPortParameters
}
import svarog.config.Cluster
import svarog.debug.HartDebugIO

class MicroTile(
    val hartBase: Int,
    val cluster: Cluster,
    val startAddress: Long,
    val instSourceIds: Seq[IdRange],
    val dataSourceIds: Seq[IdRange]
)(override implicit val p: Parameters)
    extends LazyModule {

  private val beatBytes = cluster.isa.xlen / 8
  private def clientParams(name: String, id: IdRange) =
    TLMasterParameters.v1(
      name = name,
      sourceId = id,
      supportsProbe = TransferSizes(1, beatBytes),
      supportsGet = TransferSizes(1, beatBytes),
      supportsPutFull = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes)
    )

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

  lazy val module = new MicroTileImp(this)
}

class MicroTileImp(outer: MicroTile) extends LazyModuleImp(outer) {
  private val xlen = outer.cluster.isa.xlen
  private val numCores = outer.cluster.numCores

  val io = IO(new Bundle {
    val debug = Vec(numCores, Flipped(new HartDebugIO(xlen)))
    val debugRegData = Vec(numCores, Valid(UInt(xlen.W)))
    val halt = Output(Vec(numCores, Bool()))
  })

  require(outer.instNodes.length == numCores, "instNodes must match numCores")
  require(outer.dataNodes.length == numCores, "dataNodes must match numCores")

  private val cores = Seq.tabulate(numCores) { i =>
    val hartId = outer.hartBase + i
    val (instOut, instEdge) = outer.instNodes(i).out(0)
    val (dataOut, dataEdge) = outer.dataNodes(i).out(0)
    val cpu = Module(
      new Cpu(hartId, outer.cluster, outer.startAddress, instEdge, dataEdge)
    )
    cpu.io.inst <> instOut
    cpu.io.data <> dataOut
    cpu
  }

  cores.zipWithIndex.foreach { case (cpu, i) =>
    cpu.io.debug <> io.debug(i)
    io.debugRegData(i) <> cpu.io.debugRegData
    io.halt(i) := cpu.io.halt
  }
}
