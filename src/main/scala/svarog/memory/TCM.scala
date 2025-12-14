package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.soc.SvarogConfig

// Tightly Coupled Memory (TCM)
class TCM(
    xlen: Int,
    memSizeBytes: Int,
    baseAddr: Long = 0,
    numPorts: Int = 2
) extends Module {
  require(
    memSizeBytes % (xlen / 8) == 0,
    "Memory size must be a multiple of word size"
  )
  val io = IO(new Bundle {
    val ports = Vec(numPorts, Flipped(new MemoryIO(xlen, xlen)))
  })

  val wordSize = (xlen / 8)

  private val mem =
    SyncReadMem(memSizeBytes / wordSize, Vec(wordSize, UInt(8.W)))

  for (i <- 0 until numPorts) {
    // Single cycle memory, always ready
    io.ports(i).req.ready := true.B

    val reqFire = io.ports(i).req.valid && io.ports(i).req.ready

    val addr = io.ports(i).req.bits.address
    val addrInRange =
      addr >= baseAddr
        .U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

    val wordIdx = (addr - baseAddr.U) / wordSize.U

    val enable = addrInRange && reqFire

    val readData = mem.readWrite(
      wordIdx,
      io.ports(i).req.bits.dataWrite,
      io.ports(i).req.bits.mask,
      enable,
      io.ports(i).req.bits.write
    )

    val respValid = RegNext(enable, false.B)
    io.ports(i).resp.valid := respValid
    io.ports(i).resp.bits.valid := respValid
    io.ports(i).resp.bits.dataRead := readData
  }
}

/// Wishbone adapter for TCM
class TCMWB(
    xlen: Int,
    memSizeBytes: Int,
    baseAddr: Long = 0
) extends Module
    with WishboneSlave {
  val io = IO(Flipped(new WishboneIO(addrWidth = xlen, dataWidth = xlen)))

  def addrStart: Long = baseAddr
  def addrEnd: Long = baseAddr + memSizeBytes

  val tcm = Module(
    new TCM(
      xlen,
      memSizeBytes = memSizeBytes,
      baseAddr = baseAddr,
      numPorts = 1
    )
  )

  private val wordBytes = xlen / 8

  val lastReqValid = RegInit(false.B)
  val lastReq = RegInit(0.U.asTypeOf(new MemoryRequest(xlen, xlen)))

  val curReq = Wire(new MemoryRequest(xlen, xlen))
  curReq := lastReq

  val reqValid = WireInit(lastReqValid)

  when(io.cycleActive && io.strobe) {
    val wbDataBytes = Wire(Vec(wordBytes, UInt(8.W)))
    for (i <- 0 until wordBytes) {
      wbDataBytes(i) := io.dataToSlave(8 * (i + 1) - 1, 8 * i)
    }

    lastReqValid := true.B
    lastReq.address := io.addr
    lastReq.dataWrite := wbDataBytes
    lastReq.write := io.writeEnable
    lastReq.mask := io.sel

    reqValid := true.B
    curReq.address := io.addr
    curReq.dataWrite := wbDataBytes
    curReq.write := io.writeEnable
    curReq.mask := io.sel
  }.elsewhen(io.cycleActive === false.B) {
    lastReqValid := false.B
    reqValid := false.B
  }

  tcm.io.ports(0).req.valid := reqValid
  tcm.io.ports(0).req.bits := curReq

  io.ack := false.B
  io.stall := false.B
  io.dataToMaster := 0.U
  io.error := false.B

  tcm.io.ports(0).resp.ready := true.B

  when(reqValid) {
    io.ack := tcm.io.ports(0).resp.valid
    io.dataToMaster := Cat(tcm.io.ports(0).resp.bits.dataRead)
    io.error := tcm.io.ports(0).resp.bits.valid
    io.stall := !tcm.io.ports(0).resp.valid
  }
}
