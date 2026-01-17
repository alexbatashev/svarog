package svarog.memory

import chisel3._
import chisel3.util._
import svarog.bits.asLE

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
  val valid = Bool()
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
