package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import svarog.micro.MemWidth

/**
 * Simple synchronous memory for testing
 *
 * This is a behavioral model - perfect for testing, can be synthesized to BRAM.
 * - 1 cycle read latency (synchronous read)
 * - Stores complete in 1 cycle
 * - Supports byte, half-word, and word access
 * - Properly handles alignment and sign/zero extension
 */
class SimpleMemory(
  xlen: Int = 32,
  memSizeBytes: Int = 4096,  // 4KB default
  initFile: Option[String] = None
) extends Module {
  // Memory is the responder, so flip the interface (consumes req, produces resp)
  val io = IO(Flipped(new DataCacheIO(xlen)))

  // Memory array - stored as 32-bit words
  // Using Mem (combinational) instead of SyncReadMem for faster testing
  val numWords = memSizeBytes / 4
  val mem = Mem(numWords, UInt(32.W))
  initFile.foreach(loadMemoryFromFile(mem, _))

  // Simplified combinational memory for testing
  // Real implementation would have multi-cycle latency

  val wordAddr = io.req.bits.addr >> 2
  val byteOffset = io.req.bits.addr(1, 0)

  // Always ready to accept requests
  io.req.ready := true.B

  // Default response
  io.resp.valid := false.B
  io.resp.bits.data := 0.U

  when(io.req.fire) {
    when(io.req.bits.write) {
      // STORE - write to memory
      val writeData = WireDefault(0.U(32.W))
      val writeMask = WireDefault(0.U(4.W))

      switch(io.req.bits.memWidth) {
        is(MemWidth.BYTE) {
          val byteData = io.req.bits.data(7, 0)
          writeData := byteData << (byteOffset << 3)
          writeMask := 1.U << byteOffset
        }
        is(MemWidth.HALF) {
          val halfData = io.req.bits.data(15, 0)
          writeData := halfData << (byteOffset << 3)
          writeMask := Mux(byteOffset(0), "b1100".U, "b0011".U)
        }
        is(MemWidth.WORD) {
          writeData := io.req.bits.data
          writeMask := "b1111".U
        }
      }

      // Read-modify-write
      val oldData = mem.read(wordAddr)
      val byteMaskExpanded = Cat(
        Fill(8, writeMask(3)),
        Fill(8, writeMask(2)),
        Fill(8, writeMask(1)),
        Fill(8, writeMask(0))
      )
      val newData = (writeData & byteMaskExpanded) | (oldData & ~byteMaskExpanded)
      mem.write(wordAddr, newData)

    }.otherwise {
      // LOAD - read from memory and respond immediately
      val wordData = mem.read(wordAddr)
      val shiftedData = wordData >> (byteOffset << 3)
      val loadedData = WireDefault(0.U(32.W))

      switch(io.req.bits.memWidth) {
        is(MemWidth.BYTE) {
          val byteVal = shiftedData(7, 0)
          loadedData := Mux(io.req.bits.unsigned,
            byteVal,
            Cat(Fill(24, byteVal(7)), byteVal)
          )
        }
        is(MemWidth.HALF) {
          val halfVal = shiftedData(15, 0)
          loadedData := Mux(io.req.bits.unsigned,
            halfVal,
            Cat(Fill(16, halfVal(15)), halfVal)
          )
        }
        is(MemWidth.WORD) {
          loadedData := shiftedData
        }
      }

      io.resp.valid := true.B
      io.resp.bits.data := loadedData
    }
  }
}
