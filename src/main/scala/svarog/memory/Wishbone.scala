package svarog.memory

import chisel3._
import chisel3.util._

class WishboneIO(val addrWidth: Int = 32, val dataWidth: Int = 32)
    extends Bundle {
  // Master -> Slave signals
  val cycleActive = Output(Bool())
  val strobe = Output(Bool())
  val writeEnable = Output(Bool())
  val addr = Output(UInt(addrWidth.W))
  val dataToSlave = Output(UInt(dataWidth.W))
  val sel = Output(Vec(dataWidth / 8, Bool())) // Byte select (one per byte)

  // Slave -> Master signals
  val ack = Input(Bool()) // Transaction complete
  val stall = Input(Bool()) // Slave not ready (backpressure)
  val dataToMaster = Input(UInt(dataWidth.W))
  val error = Input(Bool())
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

  def pipelined: Boolean = false

  def inAddrSpace(addr: UInt): Bool = {
    addr >= addrStart.U && addr < addrEnd.U
  }
}

private object ArbiterCtrl {
  def apply(request: Seq[Bool]): Seq[Bool] = request.length match {
    case 0 => Seq()
    case 1 => Seq(true.B)
    case _ => true.B +: request.tail.init.scanLeft(request.head)(_ || _).map(!_)
  }
}

object WishboneRouter {
  def apply(masters: Seq[WishboneMaster], slaves: Seq[WishboneSlave]): Unit = {
    require(masters.nonEmpty, "WishboneRouter requires at least one master")
    require(slaves.nonEmpty, "WishboneRouter requires at least one slave")

    generateBus(masters, slaves)
  }

  def generateBus(masters: Seq[WishboneMaster], slaves: Seq[WishboneSlave]) = {
    val chosen = RegInit(0.U)
    val fire = RegInit(false.B)
    val lastGrant = RegEnable(chosen, fire)

    lazy val grantMask = (0 until masters.length).map(_.asUInt > lastGrant)
    lazy val validMask =
      masters.map(_.io).zip(grantMask).map { case (in, g) =>
        in.cycleActive && g
      }

    val ctrl = ArbiterCtrl(
      (0 until masters.length).map(i => validMask(i)) ++ masters.map(
        _.io.cycleActive
      )
    )
    val grant =
      (0 until masters.length).map(i =>
        ctrl(i) && grantMask(i) || ctrl(i + masters.length)
      )

    val choice = WireDefault((masters.length - 1).asUInt)

    chosen := choice

    for (i <- masters.length - 2 to 0 by -1) {
      when(masters(i).io.cycleActive) { choice := i.asUInt }
    }
    for (i <- masters.length - 1 to 1 by -1) {
      when(validMask(i)) { choice := i.asUInt }
    }

    for (i <- 0 to masters.length) {
      when(chosen =/= i.asUInt && masters(i).io.cycleActive) {
        masters(i).io.stall := true.B
      }
      when(chosen === i.asUInt && masters(i).io.cycleActive) {
        fire := true.B
        chosen := i.asUInt

        slaves.foreach { s =>
          when(
            s.addrStart.asUInt <= masters(
              i
            ).io.addr && s.addrEnd.asUInt >= masters(
              i
            ).io.addr
          ) {
            s.io <> masters(i).io
          }.otherwise {
            s.io.cycleActive := false.B
          }
        }
      }
    }
  }
}
