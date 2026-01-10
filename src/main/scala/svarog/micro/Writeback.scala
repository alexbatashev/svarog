package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.RegFileWriteIO
import svarog.bits.CSRWriteMasterIO
import svarog.decoder.OpType

class Writeback(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new MemResult(xlen)))
    val regFile = Flipped(new RegFileWriteIO(xlen))
    val csrWrite = Flipped(new CSRWriteMasterIO(xlen))
    val hazard = Valid(UInt(5.W))
    val csrHazard = Valid(new HazardUnitCSRIO)
    val instret = Output(Bool())
    val debugPC = Valid(UInt(xlen.W))
    val debugStore = Valid(UInt(xlen.W)) // For watchpoint support
    val halt = Input(Bool())
  })

  // Multi-cycle trap commit state machine
  // When handling OpType.INVALID, we need 3 cycles to write mepc, mcause, mtval
  val trapCommitState = RegInit(0.U(2.W))
  val trapLatch = Reg(new MemResult(xlen))
  val isTrapCommit = RegInit(false.B)

  // Ready when not in middle of trap commit
  io.in.ready := !isTrapCommit

  io.hazard.valid := io.in.valid
  io.hazard.bits := io.in.bits.rd

  io.csrHazard.valid := io.in.valid && io.in.bits.csrWrite
  io.csrHazard.bits.addr := io.in.bits.csrAddr
  io.csrHazard.bits.isWrite := io.in.bits.csrWrite
  io.csrHazard.bits.isTrap := false.B

  io.debugPC.valid := io.in.valid
  io.debugPC.bits := io.in.bits.pc

  // Watchpoint support: signal store operations
  io.debugStore.valid := io.in.valid && io.in.bits.isStore
  io.debugStore.bits := io.in.bits.storeAddr

  io.regFile.writeEn := false.B
  io.regFile.writeAddr := 0.U
  io.regFile.writeData := 0.U

  io.csrWrite.valid := false.B
  io.csrWrite.addr := 0.U
  io.csrWrite.data := 0.U

  io.instret := false.B

  // Trap commit state machine
  when(io.in.fire && io.in.bits.opType === OpType.INVALID) {
    // Start trap commit sequence
    isTrapCommit := true.B
    trapCommitState := 0.U
    trapLatch := io.in.bits
  }

  when(isTrapCommit) {
    // Multi-cycle trap CSR writes
    io.csrWrite.valid := true.B

    switch(trapCommitState) {
      is(0.U) {
        // Write mepc (exception PC)
        io.csrWrite.addr := 0x341.U
        io.csrWrite.data := trapLatch.pc
        trapCommitState := 1.U
      }
      is(1.U) {
        // Write mcause (trap cause)
        io.csrWrite.addr := 0x342.U
        io.csrWrite.data := 2.U  // Illegal instruction, bit 31=0 for exception
        trapCommitState := 2.U
      }
      is(2.U) {
        // Write mtval (trap value - faulting instruction)
        io.csrWrite.addr := 0x343.U
        io.csrWrite.data := trapLatch.instruction
        // Trap commit complete
        isTrapCommit := false.B
        trapCommitState := 0.U
        io.instret := !io.halt
      }
    }

    // Set CSR hazard to indicate trap CSR writes are in progress
    io.csrHazard.valid := true.B
    io.csrHazard.bits.addr := io.csrWrite.addr
    io.csrHazard.bits.isWrite := true.B
  }

  // Normal instruction commit
  when(io.in.valid && io.in.bits.opType =/= OpType.INVALID) {
    io.regFile.writeEn := io.in.bits.gprWrite
    io.regFile.writeAddr := io.in.bits.rd
    io.regFile.writeData := io.in.bits.gprData

    io.csrWrite.valid := io.in.bits.csrWrite
    io.csrWrite.addr := io.in.bits.csrAddr
    io.csrWrite.data := io.in.bits.csrData

    io.instret := !io.halt
  }
}
