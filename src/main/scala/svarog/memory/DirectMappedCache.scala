package svarog.memory

import chisel3._
import chisel3.util._

class DirectMappedCache(cacheSize: Int, lineSize: Int, xlen: Int) extends Module {
  val io = IO(new L1CacheIO(xlen, 32, lineSize))

  val numLines = cacheSize / lineSize
  val wordsPerLine = lineSize / 4 // Assuming 32-bit words (4 bytes)
  val offsetBits   = log2Ceil(lineSize)
  val indexBits    = log2Ceil(numLines)
  val tagBits      = xlen - indexBits - offsetBits

  // --- Internal Storage ---
  val valid_bits = RegInit(VecInit(Seq.fill(numLines)(false.B)))
  val tags       = Reg(Vec(numLines, UInt(tagBits.W)))
  // A Synchronous Read Memory for the data lines. Data is available 1 cycle after addr.
  val data_store = SyncReadMem(numLines, Vec(wordsPerLine, UInt(32.W)))

  // --- Address Decomposition ---
  // Decompose the address from the current CPU request
  val req_addr = io.cpu.addr
  val req_tag  = req_addr(xlen - 1, xlen - tagBits)
  val req_idx  = req_addr(xlen - tagBits - 1, offsetBits)
  // Which word within the line to select
  val req_off  = req_addr(offsetBits - 1, 2) // Word offset (divided by 4)

  // --- State Machine Definition ---
  object State extends ChiselEnum {
    val sIdle, sRefill = Value
  }
  val state = RegInit(State.sIdle)

  // Register to hold the tag of a missed request during refill
  val saved_tag = Reg(UInt(tagBits.W))

  val stored_tag = tags(req_idx)

  val hit = (state === State.sIdle) && io.cpu.reqValid && valid_bits(req_idx) && (stored_tag === req_tag)

  io.cpu.respValid := hit
  io.mem.reqValid  := false.B
  io.mem.addr      := Cat(Mux(state === State.sIdle, req_tag, saved_tag), req_idx, 0.U(offsetBits.W))
  io.mem.writeEn   := false.B
  io.mem.dataOut   := DontCare

  val read_data_line = data_store.read(req_idx, io.cpu.reqValid && state === State.sIdle)
  io.cpu.data := read_data_line(req_off)

  switch(state) {
    is(State.sIdle) {
      // If the request is a miss, transition to Refill state.
      when(io.cpu.reqValid && !hit) {
        state     := State.sRefill
        saved_tag := req_tag // Latch the tag for the refill request
      }
    }
    is(State.sRefill) {
      // Assert request to main memory
      io.mem.reqValid := true.B

      // When memory provides the data, write it into the cache and return to Idle.
      when(io.mem.respValid) {
        state              := State.sIdle
        // Find where to write using the saved index (which is `req_idx` from the cycle of the miss)
        val write_idx = io.mem.addr(xlen - tagBits - 1, offsetBits)
        data_store.write(write_idx, io.mem.dataIn)
        tags(write_idx)       := saved_tag
        valid_bits(write_idx) := true.B
      }
    }
  }
}
