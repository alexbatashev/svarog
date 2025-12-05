package svarog.memory

import chisel3._
import chisel3.util._

// Wishbone B4 pipelined bus interface
class WishboneIO(val addrWidth: Int = 32, val dataWidth: Int = 32)
    extends Bundle {
  // Master -> Slave signals
  val cyc = Output(Bool()) // Bus cycle active
  val stb = Output(Bool()) // Valid transaction
  val we = Output(Bool()) // Write enable
  val addr = Output(UInt(addrWidth.W)) // Address
  val dataToSlave = Output(UInt(dataWidth.W)) // Write data
  val sel = Output(Vec(dataWidth / 8, Bool())) // Byte select (one per byte)

  // Slave -> Master signals
  val ack = Input(Bool()) // Transaction complete
  val stall = Input(Bool()) // Slave not ready (backpressure)
  val dataToMaster = Input(UInt(dataWidth.W)) // Read data
  val err = Input(Bool()) // Error response
}

// Trait for modules that act as Wishbone masters
trait WishboneMaster extends Module {
  val io: WishboneIO
}

// Trait for modules that act as Wishbone slaves
trait WishboneSlave extends Module {
  val io: WishboneIO

  // Address space for this slave
  def addrStart: BigInt
  def addrEnd: BigInt

  def inAddrSpace(addr: UInt): Bool = {
    addr >= addrStart.U && addr < addrEnd.U
  }
}

object WishboneRouter {
  def apply(masters: Seq[WishboneMaster], slaves: Seq[WishboneSlave]): Unit = {}
}
