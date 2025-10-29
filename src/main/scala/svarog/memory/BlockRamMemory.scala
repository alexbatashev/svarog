package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import svarog.micro.MemWidth

/**
 * Synthesizable data memory that infers FPGA block RAM.
 *
 * The interface matches [[DataCacheIO]]:
 * - requests handshake through `req`
 * - loads return one cycle after the request (synchronous read)
 * - stores complete in a single cycle with byte enables
 *
 * The memory stores 32-bit words and supports byte, half-word, and word transfers.
 * Optional `initFile` lets you preload contents during synthesis/simulation.
 */
class BlockRamMemory(
  xlen: Int = 32,
  memSizeBytes: Int = 4096,
  initFile: Option[String] = None
) extends Module {
  require(xlen == 32, "BlockRamMemory currently supports 32-bit XLEN only")
  require(memSizeBytes % (xlen / 8) == 0, "Memory size must be a multiple of word size")

  val io = IO(Flipped(new DataCacheIO(xlen)))

  private val bytesPerWord = xlen / 8
  private val numWords = memSizeBytes / bytesPerWord

  // SyncReadMem infers block RAM on Xilinx when the read data is registered.
  private val mem = SyncReadMem(numWords, Vec(bytesPerWord, UInt(8.W)))
  initFile.foreach(loadMemoryFromFileInline(mem, _))

  // Default interface values
  io.req.ready := true.B
  io.resp.valid := false.B
  io.resp.bits.data := 0.U

  // Internal state to track an outstanding load
  private val loadPending = RegInit(false.B)

  // Only accept a new request when no load response is waiting
  io.req.ready := !loadPending

  // Split request handling for clarity
  val addr = io.req.bits.addr
  val wordAddr = addr >> log2Ceil(bytesPerWord)
  val byteOffset = addr(log2Ceil(bytesPerWord) - 1, 0)

  val isLoad = !io.req.bits.write
  val isStore = io.req.bits.write
  val requestAccepted = io.req.fire

  val doLoad = requestAccepted && isLoad
  val readVec = mem.read(wordAddr, doLoad)
  val readData = readVec.asUInt
  val loadByteOffsetReg = RegEnable(byteOffset, 0.U(log2Ceil(bytesPerWord).W), doLoad)
  val loadWidthReg = RegEnable(io.req.bits.memWidth, MemWidth.WORD, doLoad)
  val loadUnsignedReg = RegEnable(io.req.bits.unsigned, false.B, doLoad)
  val dataReady = RegNext(doLoad, init = false.B)
  val loadDataHold = RegInit(0.U(xlen.W))
  when(dataReady) {
    loadDataHold := readData
  }
  val loadDataWord = Mux(dataReady, readData, loadDataHold)

  // --- Store path (write with per-byte mask) ---
  when(requestAccepted && isStore) {
    val shiftAmount = (byteOffset << 3).asUInt
    val alignedData = (io.req.bits.data << shiftAmount)(xlen - 1, 0)

    val writeMask = WireDefault(0.U(bytesPerWord.W))
    switch(io.req.bits.memWidth) {
      is(MemWidth.BYTE) {
        writeMask := 1.U << byteOffset
      }
      is(MemWidth.HALF) {
        writeMask := Mux(byteOffset(0), "b1100".U, "b0011".U)
      }
      is(MemWidth.WORD) {
        writeMask := "b1111".U
      }
      is(MemWidth.DWORD) {
        writeMask := "b1111".U
      }
    }

    val writeDataVec = Wire(Vec(bytesPerWord, UInt(8.W)))
    for (i <- 0 until bytesPerWord) {
      val hi = (i + 1) * 8 - 1
      val lo = i * 8
      writeDataVec(i) := alignedData(hi, lo)
    }

    mem.write(wordAddr, writeDataVec, writeMask.asBools)
  }

  // --- Load path (synchronous read, data available next cycle) ---
  when(doLoad) {
    loadPending := true.B
  }

  when(loadPending) {
    val shiftAmount = (loadByteOffsetReg << 3).asUInt
    val shifted = loadDataWord >> shiftAmount
    val loadResult = WireDefault(0.U(xlen.W))

    switch(loadWidthReg) {
      is(MemWidth.BYTE) {
        val byteVal = shifted(7, 0)
        loadResult := Mux(loadUnsignedReg, byteVal, Cat(Fill(24, byteVal(7)), byteVal))
      }
      is(MemWidth.HALF) {
        val halfVal = shifted(15, 0)
        loadResult := Mux(loadUnsignedReg, halfVal, Cat(Fill(16, halfVal(15)), halfVal))
      }
      is(MemWidth.WORD) {
        loadResult := shifted
      }
      is(MemWidth.DWORD) {
        loadResult := shifted
      }
    }

    io.resp.valid := true.B
    io.resp.bits.data := loadResult

    when(io.resp.ready) {
      loadPending := false.B
    }
  }
}
