package svarog.memory

import chisel3._
import chisel3.util._
import svarog.soc.SvarogConfig

// Tightly Coupled Memory (TCM)
class TCM(
    xlen: Int,
    memSizeBytes: Int,
    baseAddr: Long = 0,
    debugEnabled: Boolean = false
) extends Module {
  require(
    memSizeBytes % (xlen / 8) == 0,
    "Memory size must be a multiple of word size"
  )
  val io = IO(new Bundle {
    val instr = Flipped(new MemoryIO(xlen, xlen))
    val data = Flipped(new MemoryIO(xlen, xlen))
  })

  val debug = if (debugEnabled) {
    Some(IO(Flipped(new DebugMemoryIO(xlen))))
  } else {
    None
  }

  private val mem =
    SyncReadMem(memSizeBytes / (xlen / 8), Vec(xlen / 8, UInt(8.W)))

  // Request is always ready (single cycle access)
  io.instr.req.ready := true.B
  io.data.req.ready := true.B

  // Response generation - SyncReadMem already provides registered output
  io.instr.resp.valid := RegNext(
    io.instr.req.valid && io.instr.req.ready,
    false.B
  )
  io.data.resp.valid := RegNext(io.data.req.valid && io.data.req.ready, false.B)

  // Instruction memory access
  val instrInRange = io.instr.req.bits.address >= baseAddr.U &&
    io.instr.req.bits.address < (baseAddr + memSizeBytes).U
  val instrAddr = io.instr.req.bits.address - baseAddr.U
  when(io.instr.req.valid && instrInRange && io.instr.req.bits.write) {
    mem.write(instrAddr / (xlen / 8).U, io.instr.req.bits.dataWrite)
  }
  io.instr.resp.bits.dataRead := mem.read(instrAddr / (xlen / 8).U)
  io.instr.resp.bits.valid := RegNext(instrInRange, false.B)

  // Data memory access
  val dataInRange = io.data.req.bits.address >= baseAddr.U &&
    io.data.req.bits.address < (baseAddr + memSizeBytes).U
  val dataAddr = io.data.req.bits.address - baseAddr.U
  val dataWordIdx = dataAddr / (xlen / 8).U
  val dataByteOffset = dataAddr % (xlen / 8).U

  // Calculate number of bytes to access based on reqWidth (0=1 byte, 1=2 bytes, 2=4 bytes, 3=8 bytes)
  val dataNumBytes = (1.U << io.data.req.bits.reqWidth)

  when(io.data.req.valid && dataInRange && io.data.req.bits.write) {
    // Generate write mask: enable only the bytes that should be written
    val writeMask = WireDefault(VecInit(Seq.fill(xlen / 8)(false.B)))
    for (i <- 0 until (xlen / 8)) {
      writeMask(i) := (i.U >= dataByteOffset) && (i.U < (dataByteOffset + dataNumBytes))
    }
    mem.write(dataWordIdx, io.data.req.bits.dataWrite, writeMask)
  }
  io.data.resp.bits.dataRead := mem.read(dataWordIdx)
  io.data.resp.bits.valid := RegNext(dataInRange, false.B)

  // Debug interface - byte-level access
  debug.foreach { dbg =>
    dbg.req.ready := true.B
    dbg.resp.valid := RegNext(dbg.req.valid && dbg.req.ready, false.B)

    val debugInRange = dbg.req.bits.address >= baseAddr.U &&
      dbg.req.bits.address < (baseAddr + memSizeBytes).U
    val byteAddr = dbg.req.bits.address - baseAddr.U
    val wordIdx = byteAddr / (xlen / 8).U
    val byteOffset = byteAddr % (xlen / 8).U

    when(dbg.req.valid && debugInRange && dbg.req.bits.write) {
      // Read-modify-write: read word, update byte, write back
      val wordData = mem.read(wordIdx)
      val newWordData = WireDefault(wordData)
      newWordData(byteOffset) := dbg.req.bits.dataWrite
      mem.write(wordIdx, newWordData)
    }

    // Always read for simplicity (registered by SyncReadMem)
    val wordData = mem.read(wordIdx)
    dbg.resp.bits.dataRead := wordData(RegNext(byteOffset))
  }
}
