package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import svarog.bits.{CSRReadIO, CSRWriteIO}

/** CSR Bus Adapter - bridges the existing CSRIO interface to the CSR bus
  *
  * This adapter converts:
  *   - CSRReadIO (addr -> data) to CSR bus read
  *   - CSRWriteIO (en, addr, data) to CSR bus write
  *
  * The adapter maintains combinational read latency as required by RISC-V.
  */
class CSRBusAdapter(implicit p: Parameters) extends LazyModule {

  val node = CSRMasterNode(Seq(CSRMasterParameters(name = "cpu_csr_master")))

  lazy val module = new CSRBusAdapterImp(this)
}

class CSRBusAdapterImp(outer: CSRBusAdapter) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.out.head
  private val params = edge.params

  val io = IO(new Bundle {
    // CSRReadIO: addr is Input (from CPU), data is Output (to CPU)
    // CSRWriteIO: en, addr, data are all Input (from CPU)
    // These match the CPU's Flipped CSRIO ports
    val read = new CSRReadIO()
    val write = new CSRWriteIO()
  })

  // Convert CSRIO to CSR bus
  // Read: combinational path from addr to data
  port.m2s.addr := Mux(io.write.en, io.write.addr, io.read.addr)
  port.m2s.wdata := io.write.data
  port.m2s.wen := io.write.en
  port.m2s.ren := !io.write.en // Read when not writing

  // Return read data (combinational)
  io.read.data := port.s2m.rdata
}

/** CSR Subsystem - combines adapter and crossbar for easy instantiation
  *
  * This module instantiates:
  *   - CSRBusAdapter (master interface)
  *   - CSRXbar (address-based routing)
  *
  * External CSR slave nodes connect to the xbar.
  */
class CSRSubsystem(implicit p: Parameters) extends LazyModule {

  val adapter = LazyModule(new CSRBusAdapter)
  val xbar = LazyModule(new CSRXbar)

  // Connect adapter to xbar
  xbar.node := adapter.node

  // Expose xbar node for external slave connections
  val node = xbar.node

  lazy val module = new CSRSubsystemImp(this)
}

class CSRSubsystemImp(outer: CSRSubsystem) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val read = new CSRReadIO()
    val write = new CSRWriteIO()
  })

  // Connect to adapter
  outer.adapter.module.io.read <> io.read
  outer.adapter.module.io.write <> io.write
}
