package svarog.bpu

import chisel3._
import chisel3.util._
import svarog.decoder.InstWord

class BranchTargetBuffer(xlen: Int, entries: Int = 64) extends Module {
  require(entries >= 2, "BTB entries must be >= 2")
  require(isPow2(entries), "BTB entries must be power of two")

  val io = IO(new Bundle {
    val query = Flipped(Valid(new InstWord(xlen)))
    val target = Valid(UInt(xlen.W))
  })

  private val btbIndexBits = log2Ceil(entries)
  private val btbTagBits = xlen - btbIndexBits - 2
  require(btbTagBits > 0, "BTB tag width must be > 0")

  class BTBEntry extends Bundle {
    val valid = Bool()
    val tag = UInt(btbTagBits.W)
    val target = UInt(xlen.W)
    val isUncond = Bool()
  }

  val btb = RegInit(VecInit(Seq.fill(entries)(0.U.asTypeOf(new BTBEntry))))

  io.target := 0.U

  when(io.query.valid) {
    val btbIndex = io.query.bits.pc(2 + btbIndexBits - 1, 2)
    val btbTag = io.query.bits.pc(xlen - 1, 2 + btbIndexBits)

    val entry = btb(btbIndex)
    val btbHit = entry.valid && entry.tag === btbTag

    when(btbHit) {
      io.target := entry
    }
  }
}
