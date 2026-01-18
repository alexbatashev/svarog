package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** CSR addresses for interrupt handling registers */
object InterruptCSRAddrs {
  val MIE = 0x304 // Machine interrupt enable
  val MIP = 0x344 // Machine interrupt pending

  val all = Seq(MIE, MIP)
}

/** Interrupt bit positions in mie/mip */
object InterruptBits {
  val MSIP = 3 // Machine software interrupt
  val MTIP = 7 // Machine timer interrupt
  val MEIP = 11 // Machine external interrupt
}

/** Interrupt CSR IO for external interrupt signals */
class InterruptCSRIO extends Bundle {
  // External interrupt signals (active high)
  val timerInterrupt = Input(Bool())
  val softwareInterrupt = Input(Bool())
  val externalInterrupt = Input(Bool())

  // Interrupt enable outputs
  val mie = Output(UInt(64.W))
  val mip = Output(UInt(64.W))
}

/** Interrupt CSRs - mie and mip
  *
  * Addresses:
  *   - 0x304: mie (Machine interrupt enable)
  *   - 0x344: mip (Machine interrupt pending)
  *
  * mip bits:
  *   - MSIP (3): Machine software interrupt pending - writable
  *   - MTIP (7): Machine timer interrupt pending - read-only (hardware set)
  *   - MEIP (11): Machine external interrupt pending - read-only (hardware set)
  *
  * mie bits:
  *   - MSIE (3): Machine software interrupt enable - writable
  *   - MTIE (7): Machine timer interrupt enable - writable
  *   - MEIE (11): Machine external interrupt enable - writable
  */
class InterruptCSR(implicit p: Parameters) extends LazyModule {

  val node = CSRSlaveNode(
    Seq(CSRSlaveParameters(InterruptCSRAddrs.all, name = "interrupt_csr"))
  )

  lazy val module = new InterruptCSRImp(this)
}

class InterruptCSRImp(outer: InterruptCSR) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.in.head
  private val params = edge.params

  val io = IO(new InterruptCSRIO)

  // mie register - interrupt enable
  // Writable bits: MSIE(3), MTIE(7), MEIE(11)
  private val mie = RegInit(0.U(params.dataBits.W))

  // mip register - interrupt pending
  // MSIP(3) is writable, MTIP(7) and MEIP(11) are read-only hardware-set
  private val msip = RegInit(false.B) // Software interrupt pending (writable)

  // Construct mip from hardware signals and software-writable MSIP
  private val mipValue = Cat(
    0.U((params.dataBits - 12).W),
    io.externalInterrupt, // MEIP (bit 11)
    0.U(3.W), // bits 10:8
    io.timerInterrupt, // MTIP (bit 7)
    0.U(3.W), // bits 6:4
    msip, // MSIP (bit 3)
    0.U(3.W) // bits 2:0
  )

  // Address decode
  private val addr = port.m2s.addr
  private val selMie = addr === InterruptCSRAddrs.MIE.U
  private val selMip = addr === InterruptCSRAddrs.MIP.U

  // Any address hit
  private val hit = selMie || selMip

  // Read mux
  port.s2m.rdata := MuxCase(
    0.U,
    Seq(
      selMie -> mie,
      selMip -> mipValue
    )
  )
  port.s2m.hit := hit

  // Writable mask for mie: MSIE(3), MTIE(7), MEIE(11)
  private val mieWriteMask =
    ((1 << InterruptBits.MSIP) | (1 << InterruptBits.MTIP) | (1 << InterruptBits.MEIP))
      .U(
        params.dataBits.W
      )

  // Write handling
  when(port.m2s.wen) {
    when(selMie) {
      mie := port.m2s.wdata & mieWriteMask
    }
    when(selMip) {
      // Only MSIP is writable in mip
      msip := port.m2s.wdata(InterruptBits.MSIP)
    }
  }

  // Output connections
  io.mie := mie
  io.mip := mipValue
}
