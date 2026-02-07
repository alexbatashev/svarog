package svarog.micro

import chisel3._
import chisel3.util._

class BranchUpdate(xlen: Int) extends Bundle {
  val pc = UInt(xlen.W)
  val targetPC = UInt(xlen.W)
  val taken = Bool()
  val isBranch = Bool() // Conditional branch (B-type)
  val isUncond = Bool() // Unconditional control transfer (JAL/JALR/MRET)
}

class BranchPredictor(
    xlen: Int,
    btbEntries: Int = 64,
    bhtEntries: Int = 64
) extends Module {
  require(btbEntries >= 2, "BTB entries must be >= 2")
  require(bhtEntries >= 2, "BHT entries must be >= 2")
  require(isPow2(btbEntries), "BTB entries must be power of two")
  require(isPow2(bhtEntries), "BHT entries must be power of two")

  val io = IO(new Bundle {
    val queryPC = Input(UInt(xlen.W))
    val predictedTaken = Output(Bool())
    val predictedTarget = Output(UInt(xlen.W))
    val update = Flipped(Valid(new BranchUpdate(xlen)))
  })

  private val btbIndexBits = log2Ceil(btbEntries)
  private val bhtIndexBits = log2Ceil(bhtEntries)
  private val btbTagBits = xlen - btbIndexBits - 2
  require(btbTagBits > 0, "BTB tag width must be > 0")

  class BTBEntry extends Bundle {
    val valid = Bool()
    val tag = UInt(btbTagBits.W)
    val target = UInt(xlen.W)
    val isUncond = Bool()
  }

  val btb = RegInit(VecInit(Seq.fill(btbEntries)(0.U.asTypeOf(new BTBEntry))))
  val bht = RegInit(VecInit(Seq.fill(bhtEntries)(1.U(2.W)))) // weakly not taken

  val btbIndex = io.queryPC(2 + btbIndexBits - 1, 2)
  val btbTag = io.queryPC(xlen - 1, 2 + btbIndexBits)
  val bhtIndex = io.queryPC(2 + bhtIndexBits - 1, 2)

  val entry = btb(btbIndex)
  val btbHit = entry.valid && entry.tag === btbTag
  val bhtTaken = bht(bhtIndex)(1)

  io.predictedTaken := btbHit && (entry.isUncond || bhtTaken)
  io.predictedTarget := entry.target

  when(io.update.valid) {
    val updBtbIndex = io.update.bits.pc(2 + btbIndexBits - 1, 2)
    val updBtbTag = io.update.bits.pc(xlen - 1, 2 + btbIndexBits)
    btb(updBtbIndex).valid := true.B
    btb(updBtbIndex).tag := updBtbTag
    btb(updBtbIndex).target := io.update.bits.targetPC
    btb(updBtbIndex).isUncond := io.update.bits.isUncond

    when(io.update.bits.isBranch) {
      val updBhtIndex = io.update.bits.pc(2 + bhtIndexBits - 1, 2)
      val counter = bht(updBhtIndex)
      when(io.update.bits.taken) {
        bht(updBhtIndex) := Mux(counter === 3.U, counter, counter + 1.U)
      }.otherwise {
        bht(updBhtIndex) := Mux(counter === 0.U, counter, counter - 1.U)
      }
    }
  }
}
