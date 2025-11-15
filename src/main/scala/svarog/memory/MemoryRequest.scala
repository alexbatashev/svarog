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

  def apply(value: UInt): MemWidth.Type = value.asTypeOf(MemWidth())
}

// Memory request - only request fields
class MemoryRequest(xlen: Int, maxReqWidth: Int) extends Bundle {
  val address = UInt(xlen.W)
  val dataWrite = Vec(maxReqWidth / 8, UInt(8.W))
  val write = Bool()
  val reqWidth = UInt(log2Ceil(maxReqWidth / 8).W)
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

// Debug memory request - only request fields
class DebugMemoryRequest(xlen: Int) extends Bundle {
  val address = UInt(xlen.W)
  val dataWrite = UInt(8.W)
  val write = Bool()
}

// Debug memory response - only response fields
class DebugMemoryResponse extends Bundle {
  val dataRead = UInt(8.W)
}

// Full debug memory interface with separate request/response
class DebugMemoryIO(xlen: Int) extends Bundle {
  val req = Decoupled(new DebugMemoryRequest(xlen))
  val resp = Flipped(Decoupled(new DebugMemoryResponse))
}
