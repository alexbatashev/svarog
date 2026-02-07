package svarog.decoder

import chisel3._
import chisel3.util.Decoupled

class InstWord(xlen: Int) extends Bundle {
  val word = Output(UInt(32.W))
  val pc = Output(UInt(xlen.W))
  val predictedTaken = Output(Bool())
  val predictedTarget = Output(UInt(xlen.W))
}
