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
    val m = masters.length

    if (m == 1) {
      // Trivial case: single master, just decode to slaves.
      val master = masters.head
      slaves.foreach { s =>
        when(s.inAddrSpace(master.io.addr)) {
          s.io <> master.io
          s.io.addr := master.io.addr
        }.otherwise {
          s.io.cycleActive := false.B
          s.io.strobe := false.B
          s.io.writeEnable := false.B
        }
      }
      // No arbitration needed.
    } else {
      val idxWidth = log2Ceil(m.max(2))
      val chosen = RegInit(0.U(idxWidth.W)) // index of granted master
      val hasGrant = RegInit(false.B) // whether a master currently holds the bus

      val reqVec = VecInit(masters.map(_.io.cycleActive))
      val anyReq = reqVec.asUInt.orR

      // Round-robin search starting after current grant
      val start = chosen + 1.U
      val doubleReq = Cat(reqVec.asUInt, reqVec.asUInt)
      val shifted = (doubleReq >> start)(m - 1, 0)
      val foundNext = shifted.orR
      val enc = PriorityEncoder(shifted) // valid only if foundNext
      val rawNext = start + enc
      val nextGrant = Mux(rawNext >= m.U, rawNext - m.U, rawNext)

      when(hasGrant && reqVec(chosen)) {
        // keep current grant while cyc stays high
        hasGrant := true.B
      }.elsewhen(anyReq && foundNext) {
        hasGrant := true.B
        chosen := nextGrant
      }.otherwise {
        hasGrant := false.B
      }

      // Defaults for responses to masters
      val stallToMaster = Wire(Vec(m, Bool()))
      val ackToMaster = Wire(Vec(m, Bool()))
      val errToMaster = Wire(Vec(m, Bool()))
      val dataToMaster = Wire(
        Vec(m, UInt(masters.head.io.dataToMaster.getWidth.W))
      )

      for (i <- 0 until m) {
        stallToMaster(i) := reqVec(i) && !(hasGrant && chosen === i.U)
        ackToMaster(i) := false.B
        errToMaster(i) := false.B
        dataToMaster(i) := 0.U
      }

      // Defaults for requests to slaves
      slaves.foreach { s =>
        s.io.cycleActive := false.B
        s.io.strobe := false.B
        s.io.writeEnable := false.B
        s.io.addr := 0.U
        s.io.dataToSlave := 0.U
        s.io.sel.foreach(_ := false.B)
      }

      // Connect granted master to addressed slave and return path
      when(hasGrant) {
        for (i <- 0 until m) {
          when(chosen === i.U) {
            val mi = masters(i)
            val hit = WireInit(false.B)
            slaves.foreach { s =>
              when(s.inAddrSpace(mi.io.addr)) {
                hit := true.B
                s.io.cycleActive := mi.io.cycleActive
                s.io.strobe := mi.io.strobe
                s.io.writeEnable := mi.io.writeEnable
                s.io.addr := mi.io.addr
                s.io.dataToSlave := mi.io.dataToSlave
                s.io.sel := mi.io.sel

                stallToMaster(i) := s.io.stall
                ackToMaster(i) := s.io.ack
                errToMaster(i) := s.io.error
                dataToMaster(i) := s.io.dataToMaster
              }
            }
            // If no slave claimed the address, return an error + ack so master can proceed.
            when(!hit) {
              ackToMaster(i) := true.B
              errToMaster(i) := true.B
              stallToMaster(i) := false.B
              dataToMaster(i) := 0.U
            }
          }
        }
      }

      for (i <- 0 until m) {
        masters(i).io.stall := stallToMaster(i)
        masters(i).io.ack := ackToMaster(i)
        masters(i).io.error := errToMaster(i)
        masters(i).io.dataToMaster := dataToMaster(i)
      }
    }
  }
}
