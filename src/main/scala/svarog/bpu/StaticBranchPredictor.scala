package svarog.bpu

import chisel3._
import chisel3.util._

class StaticBranchPredictor(xlen: Int) extends BranchPredictor(xlen) {
  private val branch32 = BitPat("b?????????????????????????1100011")

  private val word = io.query.bits.word

  when(io.query.valid) {
    val offset = WireInit(0.U(xlen.W))
    val nextInstOffset = WireInit(0.U(xlen.W))
    val backward = WireInit(true.B)
    when(word === branch32) {
      backward := word(31)
      nextInstOffset := 4.U

      offset := Cat(
        word(31),
        word(7),
        word(30, 25),
        word(11, 8),
        0.U(1.W)
      ).asSInt.pad(xlen).asUInt
    }

    when(backward) {
      io.predicted.target := io.query.bits.pc + offset
      io.predicted.taken := true.B
    }.otherwise {
      io.predicted.target := io.query.bits.pc + nextInstOffset
      io.predicted.taken := false.B
    }
  }
}
