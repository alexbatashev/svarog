package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** CSR Crossbar - routes CSR requests from a single master to multiple slaves
  * based on address decoding.
  *
  * Address decode: selects = addresses.map(a => addr === a.U) Broadcast writes
  * with select gating Mux1H for read responses
  */
class CSRXbar(implicit p: Parameters) extends LazyModule {

  val node = CSRNexusNode(
    masterFn = { seq =>
      require(seq.size == 1, "CSRXbar only supports single master")
      seq.head
    },
    slaveFn = { seq =>
      CSRSlaveParameters(
        addresses = seq.flatMap(_.addresses),
        name = "xbar"
      )
    }
  )

  lazy val module = new CSRXbarImp(this)
}

class CSRXbarImp(outer: CSRXbar) extends LazyModuleImp(outer) {
  private val (masterPorts, masterEdges) = outer.node.in.unzip
  private val (slavePorts, slaveEdges) = outer.node.out.unzip

  require(masterPorts.size == 1, "CSRXbar only supports single master")
  private val master = masterPorts.head
  private val params = masterEdges.head.params

  // Build address-to-slave mapping
  // For each slave, extract its list of addresses
  private val slaveAddresses: Seq[Seq[Int]] = slaveEdges.map(_.slave.addresses)

  // Decode: for each slave, check if any of its addresses match
  private val addr = master.m2s.addr
  private val slaveSelects: Seq[Bool] = slaveAddresses.map { addrs =>
    addrs.map(a => addr === a.U).reduce(_ || _)
  }

  // Broadcast M2S signals to all slaves, gated by select
  slavePorts.zipWithIndex.foreach { case (slave, i) =>
    slave.m2s.addr := master.m2s.addr
    slave.m2s.wdata := master.m2s.wdata
    slave.m2s.wen := master.m2s.wen && slaveSelects(i)
    slave.m2s.ren := master.m2s.ren && slaveSelects(i)
  }

  // Mux1H for read responses
  private val rdatas = slavePorts.map(_.s2m.rdata)
  private val hits = slavePorts.map(_.s2m.hit)

  // Use Mux1H for selecting the response
  master.s2m.rdata := Mux1H(slaveSelects, rdatas)
  master.s2m.hit := Mux1H(slaveSelects, hits)
}
