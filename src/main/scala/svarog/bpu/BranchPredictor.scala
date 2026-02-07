package svarog.bpu

import chisel3._
import chisel3.util._

import svarog.decoder.InstWord

class Prediction(xlen: Int) extends Bundle {
  val target = Output(UInt(xlen.W))
  val taken = Output(Bool())
}

abstract class BranchPredictor(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val query = Flipped(Valid(new InstWord(xlen)))
    val predicted = new Prediction(xlen)
    val update = Flipped(Valid(new BranchUpdate(xlen)))
  })
}

class BranchUpdate(xlen: Int) extends Bundle {
  val pc = UInt(xlen.W)
  val targetPC = UInt(xlen.W)
  val taken = Bool()
  val isBranch = Bool() // Conditional branch (B-type)
  val isUncond = Bool() // Unconditional control transfer (JAL/JALR/MRET)
}

object isConditionalBranch {
  def apply(word: UInt): Bool = {
    val branch32 = BitPat("b?????????????????????????1100011")

    return word === branch32
  }
}

object isDirectJump {
  def apply(word: UInt): Bool = {
    val jump32 = BitPat("b?????????????????????????1101111")

    return word === jump32
  }
}

object isIndirectJump {
  def apply(word: UInt): Bool = {
    val jump32 = BitPat("b?????????????????????????1100111")

    return word === jump32
  }
}
