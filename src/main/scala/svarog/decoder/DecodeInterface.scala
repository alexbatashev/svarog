package svarog.decoder

import chisel3._
import chisel3.util.Decoupled

class InstWord(xlen: Int) extends Bundle {
  val word = Output(UInt(32.W))
  val pc = Output(UInt(xlen.W))
}

abstract class BaseDecoder(xlen: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val inst = Flipped(Decoupled(Vec(width, new InstWord(xlen))))
    val decoded = Decoupled(Vec(width, new MicroOp(xlen)))
  })
}
