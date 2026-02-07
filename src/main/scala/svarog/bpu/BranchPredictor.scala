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
  })
}
