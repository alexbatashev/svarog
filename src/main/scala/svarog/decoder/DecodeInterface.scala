package svarog.decoder

import chisel3._

import svarog.bpu.Prediction

class InstWord(xlen: Int) extends Bundle {
  val word = Output(UInt(32.W))
  val pc = Output(UInt(xlen.W))
}

class DecoderInput(xlen: Int) extends Bundle {
  val data = new InstWord(xlen)
  val prediction = new Prediction(xlen)
}
