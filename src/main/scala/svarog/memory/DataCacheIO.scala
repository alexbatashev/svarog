package svarog.memory

import chisel3._
import chisel3.util._
import svarog.micro.MemWidth

/** Data cache request
  */
class DataCacheReq(xlen: Int) extends Bundle {
  val addr = UInt(xlen.W)
  val data = UInt(xlen.W) // Data to write (for stores)
  val write = Bool() // true = store, false = load
  val memWidth = MemWidth() // Byte, Half, Word
  val unsigned = Bool() // For loads: zero-extend vs sign-extend
}

/** Data cache response
  */
class DataCacheResp(xlen: Int) extends Bundle {
  val data = UInt(xlen.W)
}

/** Data cache interface between CPU and memory system
  */
class DataCacheIO(xlen: Int) extends Bundle {
  // Request channel (CPU produces requests)
  val req = Decoupled(new DataCacheReq(xlen))

  // Response channel (CPU consumes responses)
  val resp = Flipped(Decoupled(new DataCacheResp(xlen)))
}
