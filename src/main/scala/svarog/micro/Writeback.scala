package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.RegFileWriteIO

class WritebackIO(xlen: Int) extends Bundle {
  // Inputs from Memory stage
  val opType = Input(OpType())
  val rd = Input(UInt(5.W))
  val regWrite = Input(Bool())
  val result = Input(UInt(xlen.W))

  // Register file write interface
  val regFile = Flipped(new RegFileWriteIO(xlen))
}

class Writeback(xlen: Int) extends Module {
  val io = IO(new WritebackIO(xlen))

  // Write result to register file
  // The regWrite signal already accounts for rd != 0 from decode stage
  io.regFile.writeEn := io.regWrite
  io.regFile.writeAddr := io.rd
  io.regFile.writeData := io.result

}
