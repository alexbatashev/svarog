package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.RegFileWriteIO
import svarog.decoder.OpType

class Writeback(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MemResult(xlen)))
    val regFile = Flipped(new RegFileWriteIO(xlen))
    val hazard = Valid(UInt(5.W))
  })

  // Always ready to accept new input
  io.in.ready := true.B

  io.hazard.valid := io.in.valid
  io.hazard.bits := io.in.bits.rd

  io.regFile.writeEn := false.B
  io.regFile.writeAddr := 0.U
  io.regFile.writeData := 0.U

  when(io.in.valid) {
    io.regFile.writeEn := io.in.bits.regWrite
    io.regFile.writeAddr := io.in.bits.rd
    io.regFile.writeData := io.in.bits.regData
  }
}
