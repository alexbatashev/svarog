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

private object WishboneArbiter {
  def apply(masters: Seq[WishboneMaster]): Vec[Bool] = {
    val lastGrant = RegInit(VecInit(Seq.fill(masters.length)(false.B)))
    // ID == numMasters allows to grant access to master 0 on first cycle
    val lastGrantId =
      Mux(
        PopCount(lastGrant) === 1.U,
        OHToUInt(lastGrant),
        masters.length.U
      )

    lazy val valid = masters.map(_.io.cycleActive)
    lazy val nextValid = (0 until masters.length)
      .zip(masters)
      .map { case (i, m) => i.U > lastGrantId && m.io.cycleActive }
    lazy val prevValid = (0 until masters.length)
      .zip(masters)
      .map { case (i, m) => i.U <= lastGrantId && m.io.cycleActive }

    val nextGrant = Mux(
      PopCount(nextValid) =/= 0.U,
      VecInit(PriorityEncoderOH(nextValid)),
      VecInit(PriorityEncoderOH(prevValid))
    )

    val inactive =
      lastGrant.zip(masters).map { case (g, m) => g && m.io.cycleActive }

    // If last granted master is inactive, cycle to next master.
    val grant = Mux(PopCount(inactive) === 0.U, nextGrant, lastGrant)

    lastGrant := grant

    assert(PopCount(grant) <= 1.U)

    grant
  }
}

object WishboneRouter {
  def apply(masters: Seq[WishboneMaster], slaves: Seq[WishboneSlave]): Unit = {
    require(masters.nonEmpty, "WishboneRouter requires at least one master")
    require(slaves.nonEmpty, "WishboneRouter requires at least one slave")

    generateBus(masters, slaves)
  }

  def generateBus(masters: Seq[WishboneMaster], slaves: Seq[WishboneSlave]) = {
    val grant = WishboneArbiter(masters)

    val cycActives = VecInit(masters.map(_.io.cycleActive))
    printf(cf"Wishbone: cycActive=$cycActives, grant=$grant\n")

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

      when(grant(i)) {
        slaves.foreach { s =>
          when(s.inAddrSpace(m.io.addr)) {
            s.io <> m.io
            when(m.io.cycleActive && m.io.strobe) {
              printf(cf"Master $i -> Slave: addr=0x${m.io.addr}%x, write=${m.io.writeEnable}, data=0x${m.io.dataToSlave}%x, sel=${m.io.sel}\n")
              when(s.io.ack) {
                printf(cf"Slave -> Master $i: ack=1, data=0x${s.io.dataToMaster}%x\n")
              }
            }
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
