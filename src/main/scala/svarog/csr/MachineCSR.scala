package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** CSR addresses for machine trap handling registers */
object MachineCSRAddrs {
  val MSTATUS = 0x300 // Machine status
  val MISA = 0x301 // Machine ISA (read-only for now)
  val MEDELEG = 0x302 // Machine exception delegation
  val MIDELEG = 0x303 // Machine interrupt delegation
  val MTVEC = 0x305 // Machine trap-handler base address
  val MSTATUSH = 0x310 // Machine status high (RV32 only)
  val MSCRATCH = 0x340 // Machine scratch register
  val MEPC = 0x341 // Machine exception program counter
  val MCAUSE = 0x342 // Machine trap cause
  val MTVAL = 0x343 // Machine trap value

  val all = Seq(
    MSTATUS,
    MISA,
    MEDELEG,
    MIDELEG,
    MTVEC,
    MSTATUSH,
    MSCRATCH,
    MEPC,
    MCAUSE,
    MTVAL
  )
}

/** Trap entry request bundle */
class TrapEntryRequest(xlen: Int) extends Bundle {
  val epc = UInt(xlen.W) // Exception PC (address of faulting instruction)
  val cause = UInt(xlen.W) // Trap cause code
}

/** Machine CSR IO for trap handling */
class MachineCSRIO(xlen: Int) extends Bundle {
  // Trap entry signal from CPU
  val trapEnter = Input(Valid(new TrapEntryRequest(xlen)))

  // MRET signal from CPU
  val mretFired = Input(Bool())

  // Outputs for trap handler dispatch
  val mtvec = Output(UInt(xlen.W))
  val mepc = Output(UInt(xlen.W))

  // mstatus outputs for interrupt enable
  val mstatus = Output(UInt(xlen.W))
}

/** Machine CSRs - trap handling registers
  *
  * Addresses:
  *   - 0x300: mstatus (Machine status register)
  *   - 0x301: misa (Machine ISA register, read-only)
  *   - 0x305: mtvec (Machine trap-handler base address)
  *   - 0x341: mepc (Machine exception program counter)
  *   - 0x342: mcause (Machine trap cause)
  *
  * Supports hardware trap entry via trapEnter interface.
  */
class MachineCSR(xlen: Int)(implicit p: Parameters) extends LazyModule {

  val node = CSRSlaveNode(
    Seq(CSRSlaveParameters(MachineCSRAddrs.all, name = "machine_csr"))
  )

  lazy val module = new MachineCSRImp(this, xlen)
}

class MachineCSRImp(outer: MachineCSR, xlen: Int) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.in.head
  private val params = edge.params

  val io = IO(new MachineCSRIO(xlen))

  // mstatus register
  // RV64: SD(63), MBE(37), SBE(36), SXL(35:34), UXL(33:32), TSR(22), TW(21),
  //       TVM(20), MXR(19), SUM(18), MPRV(17), XS(16:15), FS(14:13), MPP(12:11),
  //       VS(10:9), SPP(8), MPIE(7), UBE(6), SPIE(5), MIE(3), SIE(1)
  // For M-mode only: MPP(12:11), MPIE(7), MIE(3) are the main writable bits
  private val mstatus = RegInit(0.U(xlen.W))

  private val mstatush = RegInit(0.U(32.W))

  private val medeleg = RegInit(0.U(xlen.W))
  private val mideleg = RegInit(0.U(xlen.W))

  // misa register - read-only, reports supported extensions
  // RV64I: MXL=2 (64-bit), I extension (bit 8)
  private val misaMXL = if (xlen == 64) 2.U(2.W) else 1.U(2.W)
  private val misaExtensions = (1 << 8).U(26.W) // I extension
  private val misa = Cat(misaMXL, 0.U((xlen - 28).W), misaExtensions)

  // mtvec register - trap vector base address
  // MODE: 0=Direct, 1=Vectored (in bits [1:0])
  // BASE: trap handler base address (bits [xlen-1:2], must be 4-byte aligned)
  private val mtvec = RegInit(0.U(xlen.W))

  private val mscratch = RegInit(0.U(xlen.W))

  // mepc register - exception program counter
  private val mepc = RegInit(0.U(xlen.W))

  // mcause register - trap cause
  // Bit [xlen-1]: 1=interrupt, 0=exception
  // Bits [xlen-2:0]: cause code
  private val mcause = RegInit(0.U(xlen.W))

  private val mtval = RegInit(0.U(xlen.W))

  // Address decode
  private val addr = port.m2s.addr
  private val selStatus = addr === MachineCSRAddrs.MSTATUS.U
  private val selIsa = addr === MachineCSRAddrs.MISA.U
  private val selEdeleg = addr === MachineCSRAddrs.MEDELEG.U
  private val selIdeleg = addr === MachineCSRAddrs.MIDELEG.U
  private val selTvec = addr === MachineCSRAddrs.MTVEC.U
  private val selStatusH = addr === MachineCSRAddrs.MSTATUSH.U
  private val selScratch = addr === MachineCSRAddrs.MSCRATCH.U
  private val selEpc = addr === MachineCSRAddrs.MEPC.U
  private val selCause = addr === MachineCSRAddrs.MCAUSE.U
  private val selTval = addr === MachineCSRAddrs.MTVAL.U

  // Any address hit
  private val hit = selStatus || selIsa || selEdeleg || selIdeleg || selTvec ||
    selStatusH || selScratch || selEpc || selCause || selTval

  // Read mux
  port.s2m.rdata := MuxCase(
    0.U,
    Seq(
      selStatus -> mstatus,
      selIsa -> misa,
      selEdeleg -> medeleg,
      selIdeleg -> mideleg,
      selTvec -> mtvec,
      selStatusH -> Mux(xlen.U === 32.U, mstatush, 0.U(xlen.W)),
      selScratch -> mscratch,
      selEpc -> mepc,
      selCause -> mcause,
      selTval -> mtval
    )
  )
  port.s2m.hit := hit

  // mstatus writable mask for M-mode only implementation
  // Writable: MIE(3), MPIE(7), MPP(12:11)
  private val mstatusWriteMask =
    ((1 << 3) | (1 << 7) | (3 << 11)).U(xlen.W)

  // Write handling
  when(port.m2s.wen) {
    when(selStatus) {
      // Only write writable bits of mstatus
      mstatus := (mstatus & ~mstatusWriteMask) | (port.m2s.wdata & mstatusWriteMask)
    }
    // misa is read-only, ignore writes
    when(selTvec) {
      mtvec := port.m2s.wdata
    }
    when(selStatusH && xlen.U === 32.U) {
      mstatush := port.m2s.wdata(31, 0)
    }
    when(selEdeleg) {
      medeleg := port.m2s.wdata
    }
    when(selIdeleg) {
      mideleg := port.m2s.wdata
    }
    when(selScratch) {
      mscratch := port.m2s.wdata
    }
    when(selEpc) {
      // mepc should be aligned to IALIGN (4 bytes for RV64I without C extension)
      mepc := Cat(port.m2s.wdata(xlen - 1, 2), 0.U(2.W))
    }
    when(selCause) {
      mcause := port.m2s.wdata
    }
    when(selTval) {
      mtval := port.m2s.wdata
    }
  }

  // Hardware trap entry
  when(io.trapEnter.valid) {
    mepc := io.trapEnter.bits.epc
    mcause := io.trapEnter.bits.cause

    // Update mstatus: save MIE to MPIE, set MPP to M-mode (3), clear MIE
    val mie = mstatus(3)
    val newMstatus = Cat(
      mstatus(xlen - 1, 13),
      3.U(2.W), // MPP = M-mode
      mstatus(10, 8),
      mie, // MPIE = old MIE
      mstatus(6, 4),
      0.U(1.W), // MIE = 0
      mstatus(2, 0)
    )
    mstatus := newMstatus
  }

  // MRET handling: restore MIE from MPIE, set MPIE to 1
  when(io.mretFired) {
    val mpie = mstatus(7)
    val newMstatus = Cat(
      mstatus(xlen - 1, 13),
      3.U(2.W), // MPP stays M-mode (M-mode only implementation)
      mstatus(10, 8),
      1.U(1.W), // MPIE = 1
      mstatus(6, 4),
      mpie, // MIE = old MPIE
      mstatus(2, 0)
    )
    mstatus := newMstatus
  }

  // Output connections
  io.mtvec := mtvec
  io.mepc := mepc
  io.mstatus := mstatus
}
