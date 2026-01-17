package svarog.memory

import chisel3._
import chisel3.util._
import svarog.bits.masked
import chisel3.util.experimental.loadMemoryFromFileInline
import svarog.bits.asLE

/// Read-only memory
class ROM(
    xlen: Int,
    baseAddr: Long = 0,
    file: String
) extends Module {
  private val memSizeBytes = 65536

  require(
    memSizeBytes % (xlen / 8) == 0,
    "Memory size must be a multiple of word size"
  )
  val io = IO(Flipped(new MemoryIO(xlen, xlen)))

  private val wordSize = (xlen / 8)

  private val mem =
    SyncReadMem(memSizeBytes / wordSize, Vec(wordSize, UInt(8.W)))

  loadMemoryFromFileInline(mem, file)

  // Single cycle memory, always ready
  io.req.ready := true.B

  val addr = io.req.bits.address
  val addrInRange =
    addr >= baseAddr
      .U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

  val wordIdx = (addr - baseAddr.U) / wordSize.U

  val readData = mem.read(wordIdx)

  val enable = addrInRange && io.req.fire

  val writeEn = RegNext(io.req.bits.write)
  val respValid = RegNext(enable, false.B)
  val mask = RegNext(io.req.bits.mask, VecInit(Seq.fill(wordSize)(false.B)))

  io.resp.valid := respValid && !writeEn
  io.resp.bits.valid := respValid && addrInRange
  io.resp.bits.dataRead := masked(readData, mask)
}
