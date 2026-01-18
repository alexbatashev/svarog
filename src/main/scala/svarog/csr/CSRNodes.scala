package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import org.chipsalliance.diplomacy.nodes.{
  InwardNode,
  NodeImp,
  OutwardNode,
  RenderedEdge,
  SimpleNodeImp,
  SinkNode,
  SourceNode,
  NexusNode
}

/** Parameters for a CSR slave */
case class CSRSlaveParameters(
    addresses: Seq[Int],
    name: String = ""
) {
  require(addresses.nonEmpty, "CSR slave must have at least one address")
  require(
    addresses.forall(a => a >= 0 && a < 4096),
    "CSR addresses must be in range [0, 4095]"
  )
}

/** Parameters for a CSR master */
case class CSRMasterParameters(
    name: String = ""
)

/** Edge parameters between CSR master and slave */
case class CSREdgeParameters(
    master: CSRMasterParameters,
    slave: CSRSlaveParameters,
    params: CSRBundleParameters
)

/** Node implementation for CSR bus using SimpleNodeImp pattern */
object CSRImp
    extends SimpleNodeImp[
      CSRMasterParameters,
      CSRSlaveParameters,
      CSREdgeParameters,
      CSRBundle
    ] {

  def edge(
      pd: CSRMasterParameters,
      pu: CSRSlaveParameters,
      p: Parameters,
      sourceInfo: chisel3.experimental.SourceInfo
  ): CSREdgeParameters = {
    CSREdgeParameters(pd, pu, CSRBundleParameters.default)
  }

  def bundle(e: CSREdgeParameters): CSRBundle = new CSRBundle(e.params)

  def render(e: CSREdgeParameters): RenderedEdge =
    RenderedEdge(colour = "#00ff00", label = e.slave.addresses.mkString(","))
}

/** CSR master node - source of CSR transactions */
case class CSRMasterNode(portParams: Seq[CSRMasterParameters])(implicit
    valName: sourcecode.Name
) extends SourceNode(CSRImp)(portParams)

/** CSR slave node - sink for CSR transactions */
case class CSRSlaveNode(portParams: Seq[CSRSlaveParameters])(implicit
    valName: sourcecode.Name
) extends SinkNode(CSRImp)(portParams)

/** CSR nexus node for crossbar - aggregates multiple slaves */
case class CSRNexusNode(
    masterFn: Seq[CSRMasterParameters] => CSRMasterParameters,
    slaveFn: Seq[CSRSlaveParameters] => CSRSlaveParameters
)(implicit valName: sourcecode.Name)
    extends NexusNode(CSRImp)(masterFn, slaveFn)
