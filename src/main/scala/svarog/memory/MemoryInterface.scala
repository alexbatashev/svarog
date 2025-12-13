package svarog.memory

import chisel3._
import chisel3.util._

object MemWidth extends ChiselEnum {
  val BYTE, HALF, WORD, DWORD = Value

  def safe(funct3: UInt): (MemWidth.Type, Bool) = {
    val valid = funct3 <= 3.U
    val width = Mux(valid, funct3(1, 0).asTypeOf(MemWidth()), WORD)
    (width, valid)
  }

  def decode(funct3: UInt): (MemWidth.Type, Bool) = {
    val unsigned = funct3(2)
    val width = funct3(1, 0).asTypeOf(MemWidth())
    (width, !unsigned)
  }

  def size(w: MemWidth.Type): UInt = {
    MuxCase(
      1.U,
      Seq(
        (w === MemWidth.BYTE) -> 1.U,
        (w === MemWidth.HALF) -> 2.U,
        (w === MemWidth.WORD) -> 4.U,
        (w === MemWidth.DWORD) -> 8.U
      )
    )
  }

  def mask(maxReqWidth: Int)(w: MemWidth.Type): Vec[Bool] = {
    val numBytes = maxReqWidth / 8

    val m = Wire(Vec(numBytes, Bool()))

    for (i <- 0 until numBytes) {
      m(i) :=
        ((i < 1).B && w === MemWidth.BYTE) ||
          ((i < 2).B && w === MemWidth.HALF) ||
          ((i < 4).B && w === MemWidth.WORD) ||
          (w === MemWidth.DWORD)
    }

    m
  }

  def apply(value: UInt): MemWidth.Type = value.asTypeOf(MemWidth())
}

// Memory request - only request fields
class MemoryRequest(xlen: Int, maxReqWidth: Int) extends Bundle {
  val address = UInt(xlen.W)
  val dataWrite = Vec(maxReqWidth / 8, UInt(8.W))
  val write = Bool()
  val mask = Vec(maxReqWidth / 8, Bool())
}

// Memory response - only response fields
class MemoryResponse(maxReqWidth: Int) extends Bundle {
  val dataRead = Vec(maxReqWidth / 8, UInt(8.W))
  val valid = Input(Bool())
}

// Full memory interface with separate request/response
class MemoryIO(xlen: Int, maxReqWidth: Int) extends Bundle {
  val req = Decoupled(new MemoryRequest(xlen, maxReqWidth))
  val resp = Flipped(Decoupled(new MemoryResponse(maxReqWidth)))
}

abstract class CpuMemoryInterface(xlen: Int, maxReqWidth: Int) extends Module {
  val io = IO(new Bundle {
    val inst = Flipped(new MemoryIO(xlen, maxReqWidth))
    val data = Flipped(new MemoryIO(xlen, maxReqWidth))
  })
}

class MemWishboneHost(xlen: Int, maxReqWidth: Int)
    extends Module
    with WishboneMaster {
  val mem = IO(Flipped(new MemoryIO(xlen, maxReqWidth)))

  val io = IO(new WishboneIO(xlen, maxReqWidth))

  private val wordBytes = xlen / 8
  private val offsetWidth = log2Ceil(wordBytes)

  // Track an outstanding Wishbone transaction
  val busy = RegInit(false.B)
  val respPending = RegInit(false.B)
  val savedReq = RegInit(0.U.asTypeOf(new MemoryRequest(xlen, maxReqWidth)))
  val savedOffset = RegInit(0.U(offsetWidth.W))

  // Default Wishbone outputs
  io.cycleActive := busy
  io.strobe := busy
  io.writeEnable := savedReq.write
  io.addr := savedReq.address // byte address on the bus
  io.dataToSlave := Cat(savedReq.dataWrite.reverse)

  // Derive byte selects based on request width and address offset
  val baseMask = MemWidth.mask(xlen)(savedReq.reqWidth)
  val selVec = Wire(Vec(wordBytes, Bool()))
  for (i <- 0 until wordBytes) {
    val rel = i.U - savedOffset
    selVec(i) := (i.U >= savedOffset) && (rel < baseMask.length.U) && baseMask(
      rel
    )
  }
  io.sel := selVec

  // Accept a new memory request only when idle and no pending response
  val canAccept = !busy && !respPending
  mem.req.ready := canAccept
  when(mem.req.fire) {
    savedReq := mem.req.bits
    savedOffset := mem.req.bits.address(offsetWidth - 1, 0)
    busy := true.B
  }

  // Detect transaction completion
  val done = busy && (io.ack || io.error)
  when(done) {
    busy := false.B
    respPending := true.B
  }

  // Capture return data on ack
  val wbDataBytesReg = Reg(Vec(wordBytes, UInt(8.W)))
  when(io.ack) {
    for (i <- 0 until wordBytes) {
      wbDataBytesReg(i) := io.dataToMaster(8 * (i + 1) - 1, 8 * i)
    }
  }

  val respData = Wire(Vec(wordBytes, UInt(8.W)))
  for (i <- 0 until wordBytes) {
    val srcIdx = i.U + savedOffset
    respData(i) := Mux(srcIdx < wordBytes.U, wbDataBytesReg(srcIdx), 0.U)
  }

  // Drive memory response; hold until consumer ready
  mem.resp.valid := respPending
  mem.resp.bits.valid := respPending
  mem.resp.bits.dataRead := respData
  when(respPending && mem.resp.ready) {
    respPending := false.B
  }
}
