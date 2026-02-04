package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** CSR addresses for performance counters (Zicntr). */
object CounterCSRAddrs {
  val MCYCLE = 0xb00
  val MINSTRET = 0xb02
  val MCYCLEH = 0xb80
  val MINSTRETH = 0xb82

  val CYCLE = 0xc00
  val INSTRET = 0xc02
  val CYCLEH = 0xc80
  val INSTRETH = 0xc82

  val all = Seq(
    MCYCLE,
    MINSTRET,
    MCYCLEH,
    MINSTRETH,
    CYCLE,
    INSTRET,
    CYCLEH,
    INSTRETH
  )
}

class CounterCSRIO extends Bundle {
  val cycleTick = Input(Bool())
  val instretTick = Input(Bool())
}

/** Zicntr performance counters.
  *
  * Implements mcycle/minstret with user-mode aliases cycle/instret.
  */
class CounterCSR(xlen: Int)(implicit p: Parameters) extends LazyModule {
  val node = CSRSlaveNode(
    Seq(CSRSlaveParameters(CounterCSRAddrs.all, name = "counter_csr"))
  )

  lazy val module = new CounterCSRImp(this, xlen)
}

class CounterCSRImp(outer: CounterCSR, xlen: Int) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.in.head
  private val params = edge.params

  val io = IO(new CounterCSRIO)

  private val mcycle = RegInit(0.U(64.W))
  private val minstret = RegInit(0.U(64.W))

  private val addr = port.m2s.addr

  private val selMCycle = addr === CounterCSRAddrs.MCYCLE.U
  private val selMInstret = addr === CounterCSRAddrs.MINSTRET.U
  private val selMCycleH = addr === CounterCSRAddrs.MCYCLEH.U
  private val selMInstretH = addr === CounterCSRAddrs.MINSTRETH.U
  private val selCycle = addr === CounterCSRAddrs.CYCLE.U
  private val selInstret = addr === CounterCSRAddrs.INSTRET.U
  private val selCycleH = addr === CounterCSRAddrs.CYCLEH.U
  private val selInstretH = addr === CounterCSRAddrs.INSTRETH.U

  private val hit = selMCycle || selMInstret || selMCycleH || selMInstretH ||
    selCycle || selInstret || selCycleH || selInstretH

  private def readLow(value: UInt): UInt = {
    if (xlen == 64) {
      value
    } else {
      Cat(0.U((params.dataBits - 32).W), value(31, 0))
    }
  }

  private def readHigh(value: UInt): UInt = {
    if (xlen == 64) {
      0.U(params.dataBits.W)
    } else {
      Cat(0.U((params.dataBits - 32).W), value(63, 32))
    }
  }

  port.s2m.rdata := MuxCase(
    0.U,
    Seq(
      selMCycle -> readLow(mcycle),
      selMInstret -> readLow(minstret),
      selMCycleH -> readHigh(mcycle),
      selMInstretH -> readHigh(minstret),
      selCycle -> readLow(mcycle),
      selInstret -> readLow(minstret),
      selCycleH -> readHigh(mcycle),
      selInstretH -> readHigh(minstret)
    )
  )
  port.s2m.hit := hit

  when(io.cycleTick) {
    mcycle := mcycle + 1.U
  }

  when(io.instretTick) {
    minstret := minstret + 1.U
  }

  when(port.m2s.wen) {
    val wdataLow = port.m2s.wdata(31, 0)
    when(selMCycle) {
      if (xlen == 32) {
        mcycle := Cat(mcycle(63, 32), wdataLow)
      } else {
        mcycle := port.m2s.wdata
      }
    }
    when(selMInstret) {
      if (xlen == 32) {
        minstret := Cat(minstret(63, 32), wdataLow)
      } else {
        minstret := port.m2s.wdata
      }
    }
    when(selMCycleH && xlen.U === 32.U) {
      mcycle := Cat(wdataLow, mcycle(31, 0))
    }
    when(selMInstretH && xlen.U === 32.U) {
      minstret := Cat(wdataLow, minstret(31, 0))
    }
  }
}
