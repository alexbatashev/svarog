package svarog.interrupt

import chisel3._
import chisel3.util._
import svarog.bits.CSRBusDevice
import svarog.bits.CSRDeviceIO

/** Interrupt source signals from external devices */
class InterruptSourceIO extends Bundle {
  val mtip = Input(Bool())  // Machine timer interrupt pending (from Timer)
  val meip = Input(Bool())  // Machine external interrupt pending
  // Future S-mode support:
  // val stip = Input(Bool())  // Supervisor timer interrupt pending
  // val seip = Input(Bool())  // Supervisor external interrupt pending
}

/** Interrupt controller managing mie/mip CSRs
  *
  * This module only manages interrupt enable/pending state and reports
  * to the TrapController when an interrupt is pending and enabled.
  * The TrapController handles mstatus and trap entry/exit.
  *
  * CSRs managed:
  * - mie (0x304): Machine interrupt enable
  * - mip (0x344): Machine interrupt pending
  *
  * Interrupt bit positions (per RISC-V privileged spec):
  * - Bit 1: SSIP/SSIE (Supervisor Software) - future
  * - Bit 3: MSIP/MSIE (Machine Software)
  * - Bit 5: STIP/STIE (Supervisor Timer) - future
  * - Bit 7: MTIP/MTIE (Machine Timer)
  * - Bit 9: SEIP/SEIE (Supervisor External) - future
  * - Bit 11: MEIP/MEIE (Machine External)
  *
  * @param xlen Register width (32 or 64)
  */
class InterruptController(xlen: Int) extends Module with CSRBusDevice {
  val bus = IO(new CSRDeviceIO(xlen))

  val io = IO(new Bundle {
    val sources = new InterruptSourceIO
    val msip = Input(Bool())  // Machine software interrupt (from MSWI device)

    // Output to TrapController
    val pending = Output(Bool())         // Any interrupt pending and enabled
    val cause = Output(UInt(xlen.W))     // Highest priority interrupt cause (with MSB set)
  })

  // CSR addresses
  val MIE = 0x304.U(12.W)
  val MIP = 0x344.U(12.W)

  // Interrupt bit positions
  val SSIP_BIT = 1   // Supervisor software interrupt
  val MSIP_BIT = 3   // Machine software interrupt
  val STIP_BIT = 5   // Supervisor timer interrupt
  val MTIP_BIT = 7   // Machine timer interrupt
  val SEIP_BIT = 9   // Supervisor external interrupt
  val MEIP_BIT = 11  // Machine external interrupt

  override def addrInRange(addr: UInt): Bool = {
    addr === MIE || addr === MIP
  }

  // ============================================================================
  // CSR Registers
  // ============================================================================

  // mie: Machine interrupt enable register
  val mie = RegInit(0.U(xlen.W))

  // mip: Machine interrupt pending register
  // MTIP and MEIP are read-only (set by external sources)
  // MSIP can be set via CSR write or external MSWI device
  val msipReg = RegInit(false.B)

  // Build mip from external sources and software-controlled bits
  val mip = Wire(UInt(xlen.W))
  mip := Cat(
    0.U((xlen - MEIP_BIT - 1).W),
    io.sources.meip,           // Bit 11: MEIP
    0.U((MEIP_BIT - MTIP_BIT - 1).W),
    io.sources.mtip,           // Bit 7: MTIP
    0.U((MTIP_BIT - MSIP_BIT - 1).W),
    io.msip | msipReg,         // Bit 3: MSIP (external OR local)
    0.U(MSIP_BIT.W)
  )

  // ============================================================================
  // Interrupt Priority Logic
  // ============================================================================

  // Pending and enabled interrupts
  val pendingEnabled = mip & mie

  // Check each interrupt source (highest priority first per RISC-V spec)
  // Priority: MEI > MSI > MTI > SEI > SSI > STI
  val meipPending = pendingEnabled(MEIP_BIT)
  val msipPending = pendingEnabled(MSIP_BIT)
  val mtipPending = pendingEnabled(MTIP_BIT)

  // Any interrupt pending and enabled?
  io.pending := meipPending | msipPending | mtipPending

  // Determine interrupt cause (priority encoded)
  // Interrupt causes have MSB set (bit xlen-1 = 1)
  val interruptBit = (1.U << (xlen - 1))
  io.cause := MuxCase(0.U, Seq(
    meipPending -> (interruptBit | MEIP_BIT.U),
    msipPending -> (interruptBit | MSIP_BIT.U),
    mtipPending -> (interruptBit | MTIP_BIT.U)
  ))

  // ============================================================================
  // CSR Bus Interface
  // ============================================================================

  bus.read.bits.data := 0.U
  bus.write.bits.error := false.B

  when(bus.read.valid) {
    bus.read.bits.data := MuxLookup(bus.read.bits.addr, 0.U)(Seq(
      MIE -> mie,
      MIP -> mip
    ))
  }

  // Writable mask for mie (only M-mode bits for now)
  val mieMask = (1.U << MSIP_BIT) | (1.U << MTIP_BIT) | (1.U << MEIP_BIT)

  when(bus.write.valid && addrInRange(bus.write.bits.addr)) {
    switch(bus.write.bits.addr) {
      is(MIE) {
        mie := (bus.write.bits.data & mieMask) | (mie & ~mieMask)
      }
      is(MIP) {
        // Only MSIP is writable via CSR
        msipReg := bus.write.bits.data(MSIP_BIT)
      }
    }
  }
}
