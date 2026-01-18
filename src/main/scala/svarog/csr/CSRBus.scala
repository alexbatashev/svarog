package svarog.csr

import chisel3._
import chisel3.util._

/** Parameters for CSR bus bundles */
case class CSRBundleParameters(
    addrBits: Int = 12,
    dataBits: Int = 64
)

object CSRBundleParameters {
  val default = CSRBundleParameters()
}

/** Master-to-Slave bundle for CSR bus requests (no direction wrappers) */
class CSRBundleM2S(params: CSRBundleParameters) extends Bundle {
  val addr = UInt(params.addrBits.W)
  val wdata = UInt(params.dataBits.W)
  val wen = Bool()
  val ren = Bool()
}

object CSRBundleM2S {
  def apply(params: CSRBundleParameters): CSRBundleM2S = new CSRBundleM2S(
    params
  )
}

/** Slave-to-Master bundle for CSR bus responses (no direction wrappers) */
class CSRBundleS2M(params: CSRBundleParameters) extends Bundle {
  val rdata = UInt(params.dataBits.W)
  val hit = Bool()
}

object CSRBundleS2M {
  def apply(params: CSRBundleParameters): CSRBundleS2M = new CSRBundleS2M(
    params
  )
}

/** Combined CSR bus bundle (master's perspective)
  *
  * m2s: signals driven by master, received by slave s2m: signals driven by
  * slave, received by master (Flipped)
  *
  * For diplomatic nodes, this bundle is used on both sides with :<>= handling
  * the connection.
  */
class CSRBundle(params: CSRBundleParameters) extends Bundle {
  val m2s = new CSRBundleM2S(params)
  val s2m = Flipped(new CSRBundleS2M(params))
}

object CSRBundle {
  def apply(params: CSRBundleParameters): CSRBundle = new CSRBundle(params)
}
