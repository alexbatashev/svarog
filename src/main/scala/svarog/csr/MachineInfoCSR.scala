package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** CSR addresses for machine information registers */
object MachineInfoCSRAddrs {
  val MVENDORID = 0xf11 // Vendor ID (read-only)
  val MARCHID = 0xf12 // Architecture ID (read-only)
  val MIMPID = 0xf13 // Implementation ID (read-only)
  val MHARTID = 0xf14 // Hardware thread ID (read-only)

  val all = Seq(MVENDORID, MARCHID, MIMPID, MHARTID)
}

/** Machine Information CSRs - read-only registers
  *
  * Addresses:
  *   - 0xF11: mvendorid (0 for non-commercial)
  *   - 0xF12: marchid (0 for now)
  *   - 0xF13: mimpid (0 for now)
  *   - 0xF14: mhartid (hart ID passed as parameter)
  */
class MachineInfoCSR(hartId: Int)(implicit p: Parameters) extends LazyModule {

  val node = CSRSlaveNode(
    Seq(CSRSlaveParameters(MachineInfoCSRAddrs.all, name = "machine_info"))
  )

  lazy val module = new MachineInfoCSRImp(this, hartId)
}

class MachineInfoCSRImp(outer: MachineInfoCSR, hartId: Int)
    extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.in.head
  private val params = edge.params

  // Read-only register values
  private val mvendorid = 0.U(params.dataBits.W) // Non-commercial
  private val marchid = 0.U(params.dataBits.W) // Unspecified
  private val mimpid = 0.U(params.dataBits.W) // Unspecified
  private val mhartid = hartId.U(params.dataBits.W)

  // Address decode
  private val addr = port.m2s.addr
  private val selVendor = addr === MachineInfoCSRAddrs.MVENDORID.U
  private val selArch = addr === MachineInfoCSRAddrs.MARCHID.U
  private val selImp = addr === MachineInfoCSRAddrs.MIMPID.U
  private val selHart = addr === MachineInfoCSRAddrs.MHARTID.U

  // Any address hit
  private val hit = selVendor || selArch || selImp || selHart

  // Read mux
  port.s2m.rdata := MuxCase(
    0.U,
    Seq(
      selVendor -> mvendorid,
      selArch -> marchid,
      selImp -> mimpid,
      selHart -> mhartid
    )
  )
  port.s2m.hit := hit
}
