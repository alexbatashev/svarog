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
  def addrStart: Long
  def addrEnd: Long

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
    val hasLast = RegInit(false.B)
    val granted = RegInit(VecInit(Seq.fill(masters.length)(false.B)))
    val lastGranted = Mux(hasLast, OHToUInt(granted), 0.U)
    val locked = RegInit(false.B)

    assert(PopCount(granted) <= 1.U)

    lazy val valid = masters.map(_.io.cycleActive)
    lazy val nextValid = (0 until masters.length)
      .zip(masters)
      .map { case (i, m) => i.U > lastGranted && m.io.cycleActive }
    lazy val prevValid = (0 until masters.length)
      .zip(masters)
      .map { case (i, m) => i.U <= lastGranted && m.io.cycleActive }

    val nextGrant = Mux(
      locked,
      granted,
      Mux(
        PopCount(nextValid) =/= 0.U,
        VecInit(PriorityEncoderOH(nextValid)),
        VecInit(PriorityEncoderOH(prevValid))
      )
    )

    granted := nextGrant

    slaves.foreach { s =>
      s.io.cycleActive := false.B
      s.io.strobe := false.B
      s.io.writeEnable := false.B
      s.io.addr := 0.U
      s.io.dataToSlave := 0.U
      s.io.sel.foreach(_ := 0.U)
    }

    masters.foreach { m =>
      m.io.ack := false.B
      m.io.stall := false.B
      m.io.dataToMaster := 0.U
      m.io.error := false.B
    }

    for (i <- 0 until masters.length) {
      val m = masters(i)

      when(granted(i)) {
        locked := m.io.cycleActive

        slaves.foreach { s =>
          when(s.inAddrSpace(m.io.addr)) {
            s.io <> m.io
          }.otherwise {
            s.io.cycleActive := false.B
            s.io.strobe := false.B
            s.io.writeEnable := false.B
          }
        }
      }.otherwise {
        m.io.stall := true.B
      }
    }
  }
}
