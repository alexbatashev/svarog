package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.RegFileWriteIO
import svarog.bits.CSRWriteIO
import svarog.decoder.OpType

class Writeback(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MemResult(xlen)))
    val regFile = Flipped(new RegFileWriteIO(xlen))
    val csrFile = Flipped(new CSRWriteIO())
    val hazard = Valid(UInt(5.W))
    val csrHazard = Valid(new HazardUnitCSRIO)
    val debugPC = Valid(UInt(xlen.W))
    val debugStore = Valid(UInt(xlen.W)) // For watchpoint support
    val halt = Input(Bool())
    val retired = Output(Bool())
  })

  // Always ready - don't backpressure based on halt
  // Halt is handled by not writing registers (below)
  io.in.ready := true.B

  io.hazard.valid := io.in.valid
  io.hazard.bits := io.in.bits.rd

  io.csrHazard.valid := io.in.valid && io.in.bits.csrWrite
  io.csrHazard.bits.addr := io.in.bits.csrAddr
  io.csrHazard.bits.isWrite := io.in.bits.csrWrite

  io.debugPC.valid := io.in.valid
  io.debugPC.bits := io.in.bits.pc

  // Watchpoint support: signal store operations
  io.debugStore.valid := io.in.valid && io.in.bits.isStore
  io.debugStore.bits := io.in.bits.storeAddr

  io.retired := io.in.valid

  io.regFile.writeEn := false.B
  io.regFile.writeAddr := 0.U
  io.regFile.writeData := 0.U

  io.csrFile.en := false.B
  io.csrFile.addr := 0.U
  io.csrFile.data := 0.U

  when(io.in.valid) {
    io.regFile.writeEn := io.in.bits.gprWrite
    io.regFile.writeAddr := io.in.bits.rd
    io.regFile.writeData := io.in.bits.gprData

    io.csrFile.en := io.in.bits.csrWrite
    io.csrFile.addr := io.in.bits.csrAddr
    io.csrFile.data := io.in.bits.csrData
  }
}
