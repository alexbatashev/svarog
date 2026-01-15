package svarog.interrupt

import chisel3._
import chisel3.util._
import svarog.bits.CSRBusDevice
import svarog.bits.CSRDeviceIO

/** RISC-V trap/exception cause codes (mcause values)
  *
  * Bit xlen-1 = 1 for interrupts, 0 for exceptions
  */
object TrapCause {
  // Exceptions (synchronous, bit xlen-1 = 0)
  val InstructionAddressMisaligned = 0.U
  val InstructionAccessFault       = 1.U
  val IllegalInstruction           = 2.U
  val Breakpoint                   = 3.U
  val LoadAddressMisaligned        = 4.U
  val LoadAccessFault              = 5.U
  val StoreAddressMisaligned       = 6.U
  val StoreAccessFault             = 7.U
  val EcallFromUMode               = 8.U
  val EcallFromSMode               = 9.U
  val EcallFromMMode               = 11.U
  val InstructionPageFault         = 12.U
  val LoadPageFault                = 13.U
  val StorePageFault               = 15.U

  // Interrupt codes (will have MSB set when used in mcause)
  val SupervisorSoftwareInterrupt  = 1.U
  val MachineSoftwareInterrupt     = 3.U
  val SupervisorTimerInterrupt     = 5.U
  val MachineTimerInterrupt        = 7.U
  val SupervisorExternalInterrupt  = 9.U
  val MachineExternalInterrupt     = 11.U
}

/** Trap request from a pipeline stage or interrupt controller
  *
  * @param xlen Register width
  */
class TrapRequest(xlen: Int) extends Bundle {
  val cause = UInt(xlen.W)       // Trap cause code (for mcause)
  val tval = UInt(xlen.W)        // Trap value (faulting address/instruction for mtval)
  val pc = UInt(xlen.W)          // PC of faulting instruction (for mepc)
  val isInterrupt = Bool()       // true = interrupt, false = exception
}

/** Interface for interrupt pending signals from InterruptController */
class InterruptPendingIO(xlen: Int) extends Bundle {
  val pending = Output(Bool())           // Any interrupt pending and enabled
  val cause = Output(UInt(xlen.W))       // Highest priority interrupt cause
}

/** Central trap controller that arbitrates trap sources and manages trap CSRs
  *
  * Manages CSRs:
  * - mstatus (0x300): Machine status (MIE, MPIE, MPP)
  * - mtvec (0x305): Trap vector base address
  * - mepc (0x341): Exception program counter
  * - mcause (0x342): Trap cause
  * - mtval (0x343): Trap value
  *
  * Arbitration priority (highest first):
  * 1. Exceptions from pipeline (synchronous, must be handled immediately)
  * 2. Interrupts (asynchronous, only when mstatus.MIE=1)
  *
  * @param xlen Register width
  * @param numTrapSources Number of exception sources from pipeline
  */
class TrapController(xlen: Int, numTrapSources: Int = 1) extends Module with CSRBusDevice {
  val bus = IO(new CSRDeviceIO(xlen))

  val io = IO(new Bundle {
    // Exception inputs from pipeline stages (directly wired, active for one cycle)
    val exceptions = Vec(numTrapSources, Flipped(Valid(new TrapRequest(xlen))))

    // Interrupt pending from InterruptController
    val interruptPending = Input(Bool())
    val interruptCause = Input(UInt(xlen.W))
    // Current PC for interrupt (from fetch or commit stage)
    val currentPC = Input(UInt(xlen.W))

    // Trap output to pipeline
    val trap = Valid(new Bundle {
      val pc = UInt(xlen.W)        // Target PC (from mtvec)
      val cause = UInt(xlen.W)     // Cause for debugging/logging
    })

    // MRET handling
    val mret = Input(Bool())               // MRET instruction executing
    val mretPC = Output(UInt(xlen.W))      // Return PC (from mepc)

    // Expose mstatus for pipeline (e.g., for privilege checks)
    val mstatus = Output(UInt(xlen.W))
  })

  // CSR addresses
  val MSTATUS = 0x300.U(12.W)
  val MTVEC   = 0x305.U(12.W)
  val MEPC    = 0x341.U(12.W)
  val MCAUSE  = 0x342.U(12.W)
  val MTVAL   = 0x343.U(12.W)

  // mstatus bit positions
  val MIE_BIT  = 3
  val MPIE_BIT = 7
  val MPP_LO   = 11
  val MPP_HI   = 12

  override def addrInRange(addr: UInt): Bool = {
    addr === MSTATUS || addr === MTVEC || addr === MEPC ||
    addr === MCAUSE || addr === MTVAL
  }

  // ============================================================================
  // CSR Registers
  // ============================================================================

  val mstatus = RegInit(0.U(xlen.W))
  val mtvec = RegInit(0.U(xlen.W))
  val mepc = RegInit(0.U(xlen.W))
  val mcause = RegInit(0.U(xlen.W))
  val mtval = RegInit(0.U(xlen.W))

  // ============================================================================
  // Trap Arbitration
  // ============================================================================

  // Priority encoder for exceptions (lower index = higher priority)
  val exceptionValid = io.exceptions.map(_.valid).reduce(_ || _)
  val selectedException = PriorityMux(
    io.exceptions.map(e => (e.valid, e.bits))
  )

  // Global interrupt enable
  val globalMIE = mstatus(MIE_BIT)

  // Determine if we should take a trap
  val takeException = exceptionValid
  val takeInterrupt = !exceptionValid && globalMIE && io.interruptPending

  val takeTrap = takeException || takeInterrupt

  // Build the trap request
  val trapCause = Mux(takeException, selectedException.cause,
                  Mux(takeInterrupt, io.interruptCause, 0.U))
  val trapTval = Mux(takeException, selectedException.tval, 0.U)
  val trapPC = Mux(takeException, selectedException.pc, io.currentPC)

  // ============================================================================
  // Trap Entry
  // ============================================================================

  io.trap.valid := takeTrap
  io.trap.bits.pc := mtvec  // TODO: Support vectored mode (mtvec[1:0] = 01)
  io.trap.bits.cause := trapCause

  when(takeTrap) {
    // Save trap state
    mepc := trapPC
    mcause := trapCause
    mtval := trapTval

    // Update mstatus: save MIE to MPIE, clear MIE, set MPP to M-mode (3)
    val currentMIE = mstatus(MIE_BIT)
    mstatus := Cat(
      mstatus(xlen - 1, MPP_HI + 1),
      3.U(2.W),                           // MPP = 3 (Machine mode)
      mstatus(MPP_LO - 1, MPIE_BIT + 1),
      currentMIE,                         // MPIE = old MIE
      mstatus(MPIE_BIT - 1, MIE_BIT + 1),
      0.U(1.W),                           // MIE = 0
      mstatus(MIE_BIT - 1, 0)
    )
  }

  // ============================================================================
  // Trap Exit (MRET)
  // ============================================================================

  io.mretPC := mepc

  when(io.mret) {
    // Restore mstatus: MIE = MPIE, MPIE = 1, MPP = least privileged
    val savedMPIE = mstatus(MPIE_BIT)
    mstatus := Cat(
      mstatus(xlen - 1, MPP_HI + 1),
      0.U(2.W),                           // MPP = 0 (U-mode, or M if no U)
      mstatus(MPP_LO - 1, MPIE_BIT + 1),
      1.U(1.W),                           // MPIE = 1
      mstatus(MPIE_BIT - 1, MIE_BIT + 1),
      savedMPIE,                          // MIE = old MPIE
      mstatus(MIE_BIT - 1, 0)
    )
  }

  // Export mstatus
  io.mstatus := mstatus

  // ============================================================================
  // CSR Bus Interface
  // ============================================================================

  bus.read.bits.data := 0.U
  bus.write.bits.error := false.B

  when(bus.read.valid) {
    bus.read.bits.data := MuxLookup(bus.read.bits.addr, 0.U)(Seq(
      MSTATUS -> mstatus,
      MTVEC   -> mtvec,
      MEPC    -> mepc,
      MCAUSE  -> mcause,
      MTVAL   -> mtval
    ))
  }

  // Writable mask for mstatus
  val mstatusMask = (1.U << MIE_BIT) | (1.U << MPIE_BIT) | (3.U << MPP_LO)

  when(bus.write.valid && addrInRange(bus.write.bits.addr)) {
    switch(bus.write.bits.addr) {
      is(MSTATUS) {
        mstatus := (bus.write.bits.data & mstatusMask) | (mstatus & ~mstatusMask)
      }
      is(MTVEC) {
        mtvec := bus.write.bits.data
      }
      is(MEPC) {
        mepc := bus.write.bits.data
      }
      is(MCAUSE) {
        mcause := bus.write.bits.data
      }
      is(MTVAL) {
        mtval := bus.write.bits.data
      }
    }
  }
}
