package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.SvarogConfig
import svarog.bits.asLE
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.{
  AddressSet,
  IdRange,
  RegionType,
  TransferSizes
}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.util.SeqToAugmentedSeq

/** Tightly Coupled Memory
  *
  * @param xlen
  * @param memSizeBytes
  * @param baseAddr
  * @param numPorts
  */
class TCM(
    xlen: Int,
    memSizeBytes: Long,
    baseAddr: Long = 0,
    numPorts: Int = 1
)(implicit p: Parameters)
    extends LazyModule {
  require(
    memSizeBytes % (xlen / 8) == 0,
    "Memory size must be a multiple of word size"
  )

  val device = new SimpleDevice("tcm", Seq("svarog,tcm"))
  val wordSize = xlen / 8

  val portParams = TLSlavePortParameters.v1(
    Seq(
      TLSlaveParameters.v1(
        address = Seq(AddressSet(baseAddr, memSizeBytes - 1)),
        regionType = RegionType.UNCACHED,
        executable = true,
        supportsGet = TransferSizes(1, wordSize),
        supportsPutFull = TransferSizes(1, wordSize),
        supportsPutPartial = TransferSizes(1, wordSize),
        fifoId = Some(0)
      )
    ),
    beatBytes = wordSize,
    minLatency = 1
  )

  val node = TLManagerNode(Seq.fill(numPorts)(portParams))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    private val mem =
      SyncReadMem(memSizeBytes / wordSize, Vec(wordSize, UInt(8.W)))

    for (i <- 0 until numPorts) {
      val (in, edge) = node.in(i)

      val isGet = in.a.bits.opcode === TLMessages.Get
      val isPut = in.a.bits.opcode === TLMessages.PutFullData ||
        in.a.bits.opcode === TLMessages.PutPartialData

      in.a.ready := true.B

      val addr = in.a.bits.address
      val addrInRange =
        addr >= baseAddr
          .U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

      val wordIdx = (addr - baseAddr.U) / wordSize.U

      val denied = !addrInRange || (!isPut && !isGet)
      val enable = in.a.fire && !denied
      val mask = VecInit(in.a.bits.mask.asBools)

      val readData = mem.readWrite(
        wordIdx,
        asLE(in.a.bits.data),
        mask,
        enable,
        isPut
      )

      val respValid = RegNext(enable, false.B)
      val sizeReg = RegNext(in.a.bits.size)
      val sourceReg = RegNext(in.a.bits.source)
      val isGetReg = RegNext(isGet)
      val deniedReg = RegNext(denied)

      in.d.valid := respValid
      in.d.bits := Mux(
        isGetReg,
        edge.AccessAck(
          sourceReg,
          sizeReg,
          readData.asUInt,
          denied = deniedReg,
          corrupt = deniedReg
        ),
        edge.AccessAck(
          sourceReg,
          sizeReg,
          denied = deniedReg
        )
      )
    }
  }
  // require(
  //   memSizeBytes % (xlen / 8) == 0,
  //   "Memory size must be a multiple of word size"
  // )
  // val io = IO(new Bundle {
  //   val ports = Vec(numPorts, Flipped(new MemoryIO(xlen, xlen)))
  // })

  // val wordSize = (xlen / 8)

  // private val mem =
  //   SyncReadMem(memSizeBytes / wordSize, Vec(wordSize, UInt(8.W)))

  // for (i <- 0 until numPorts) {
  //   // Single cycle memory, always ready
  //   io.ports(i).req.ready := true.B

  //   val reqFire = io.ports(i).req.valid && io.ports(i).req.ready

  //   val addr = io.ports(i).req.bits.address
  //   val addrInRange =
  //     addr >= baseAddr
  //       .U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

  //   val wordIdx = (addr - baseAddr.U) / wordSize.U

  //   val enable = addrInRange && reqFire

  //   val readData = mem.readWrite(
  //     wordIdx,
  //     io.ports(i).req.bits.dataWrite,
  //     io.ports(i).req.bits.mask,
  //     enable,
  //     io.ports(i).req.bits.write
  //   )

  //   val respValid = RegNext(enable, false.B)
  //   io.ports(i).resp.valid := respValid
  //   io.ports(i).resp.bits.valid := respValid
  //   io.ports(i).resp.bits.dataRead := readData
  // }
}
