// Protocol-correct memory for formal model checking. Backs the request/response
// handshake with a free (uninitialized) array, so each address holds an
// arbitrary-but-stable value: bounded model checking thereby explores every
// possible program while the fetch unit can never observe a phantom response.
//
// `addrBits` bounds the modeled window (word-addressed); a short BMC trace from
// a fixed reset PC stays well inside it.

package svarog.formal

import chisel3._
import svarog.memory.MemoryIO

class FormalMemory(xlen: Int, maxReqWidth: Int, addrBits: Int = 12) extends Module {
  val io = IO(Flipped(new MemoryIO(xlen, maxReqWidth)))
  private val bytes = maxReqWidth / 8

  val mem = Mem(1 << addrBits, Vec(bytes, UInt(8.W)))
  val pending = RegInit(false.B)
  val rdata = Reg(Vec(bytes, UInt(8.W)))

  val wordIdx = io.req.bits.address(addrBits + 1, 2)

  // One outstanding request: accept only while idle, hold the response until it
  // is taken. A response can therefore never appear without a prior request.
  io.req.ready := !pending
  when(io.req.fire) {
    pending := true.B
    when(io.req.bits.write) {
      mem.write(wordIdx, io.req.bits.dataWrite, io.req.bits.mask)
    }
    rdata := mem.read(wordIdx)
  }
  when(io.resp.fire) {
    pending := false.B
  }

  io.resp.valid := pending
  io.resp.bits.valid := pending
  io.resp.bits.dataRead := rdata
}
