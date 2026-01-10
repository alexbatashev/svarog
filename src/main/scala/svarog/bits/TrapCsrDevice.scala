package svarog.bits

import chisel3._
import chisel3.util._

/** Trap-handling CSR device for RISC-V machine-mode exceptions
  *
  * Implements the following CSRs:
  * - mtvec (0x305): Trap vector base address
  * - mepc (0x341): Exception program counter
  * - mcause (0x342): Trap cause
  * - mtval (0x343): Trap value
  *
  * These CSRs are accessed via the normal CSR bus from both CSR instructions
  * (via Writeback stage) and trap handling logic (Execute reads, Writeback writes).
  *
  * @param xlen
  *   The width of the CSR registers (typically 32 or 64)
  */
class TrapCsrDevice(xlen: Int = 32) extends Module with CSRBusDevice {

  val bus = IO(new CSRDeviceIO(xlen))

  // Trap-handling CSRs - all initialize to 0
  val mtvec = RegInit(0.U(xlen.W))  // Trap vector base address
  val mepc = RegInit(0.U(xlen.W))   // Exception program counter
  val mcause = RegInit(0.U(xlen.W))  // Trap cause
  val mtval = RegInit(0.U(xlen.W))   // Trap value (faulting instruction or address)

  /** Check if an address falls within this device's CSR range
    *
    * @param addr
    *   The CSR address to check
    * @return
    *   True if this device handles the given address
    */
  def addrInRange(addr: UInt): Bool = {
    addr === 0x305.U || // mtvec
    addr === 0x341.U || // mepc
    addr === 0x342.U || // mcause
    addr === 0x343.U    // mtval
  }

  // Default outputs
  bus.read.bits.data := 0.U
  bus.write.bits.error := false.B

  // Read logic - multiplex based on address
  when(bus.read.valid) {
    bus.read.bits.data := MuxLookup(bus.read.bits.addr, 0.U)(
      Seq(
        0x305.U -> mtvec,
        0x341.U -> mepc,
        0x342.U -> mcause,
        0x343.U -> mtval
      )
    )
  }

  // Write logic - switch on address
  when(bus.write.valid && addrInRange(bus.write.bits.addr)) {
    switch(bus.write.bits.addr) {
      is(0x305.U) {
        mtvec := bus.write.bits.data
      }
      is(0x341.U) {
        mepc := bus.write.bits.data
      }
      is(0x342.U) {
        mcause := bus.write.bits.data
      }
      is(0x343.U) {
        mtval := bus.write.bits.data
      }
    }
  }
}
