package svarog.interrupt

import chisel3._
import chisel3.util._

/** Interrupt request bundle for the CLINT output */
class InterruptRequest(xlen: Int) extends Bundle {
  val cause = UInt(xlen.W)
  val epc = UInt(xlen.W)
}

/** Core Local Interrupter (CLINT) - interrupt arbitration logic.
  *
  * Combines MIP & MIE with MSTATUS.MIE check and outputs interrupt request at
  * instruction boundaries.
  *
  * Priority: MEIP(11) > MSIP(3) > MTIP(7)
  *
  * This follows SiFive CLINT behavior where:
  *   - Timer interrupts (MTIP) are set by mtime >= mtimecmp
  *   - Software interrupts (MSIP) are set by memory-mapped MSIP registers
  *   - External interrupts (MEIP) come from PLIC (future extension)
  */
class CoreLocalInterrupter(xlen: Int) extends Module {
  val io = IO(new Bundle {
    // CSR inputs
    val mie = Input(UInt(xlen.W))
    val mip = Input(UInt(xlen.W))
    val mstatus = Input(UInt(xlen.W))

    // Instruction boundary detection
    val validInstruction = Input(Bool()) // Instruction completing in Execute
    val instructionPC = Input(UInt(xlen.W))

    // Interrupt output
    val interruptRequest = Valid(new InterruptRequest(xlen))
  })

  // Global interrupt enable from MSTATUS.MIE (bit 3)
  val globalIE = io.mstatus(3)

  // Pending and enabled interrupts
  val pendingEnabled = io.mip & io.mie
  val msip = pendingEnabled(3)  // Machine software interrupt
  val mtip = pendingEnabled(7)  // Machine timer interrupt
  val meip = pendingEnabled(11) // Machine external interrupt

  // Priority encoder: MEIP > MSIP > MTIP
  // Interrupt cause code has MSB set to 1 for interrupts
  val interruptBit = (1L << (xlen - 1)).U(xlen.W)
  val cause = MuxCase(
    0.U,
    Seq(
      meip -> (interruptBit | 11.U),
      msip -> (interruptBit | 3.U),
      mtip -> (interruptBit | 7.U)
    )
  )

  val anyPending = msip || mtip || meip

  // Interrupt fires when:
  // - Global interrupts are enabled (MSTATUS.MIE = 1)
  // - Any interrupt is pending AND enabled
  // - An instruction is completing (clean instruction boundary)
  io.interruptRequest.valid := globalIE && anyPending && io.validInstruction
  io.interruptRequest.bits.cause := cause
  // EPC points to next instruction (the one that would have executed)
  io.interruptRequest.bits.epc := io.instructionPC + 4.U
}
