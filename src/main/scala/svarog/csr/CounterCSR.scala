package svarog.csr

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** CSR addresses for performance counters.
  *
  * Implements Zicntr (cycle/instret) and a fixed subset of Zihpm counters.
  */
object CounterCSRAddrs {
  val MCYCLE = 0xb00
  val MINSTRET = 0xb02
  val MCYCLEH = 0xb80
  val MINSTRETH = 0xb82

  val CYCLE = 0xc00
  val INSTRET = 0xc02
  val CYCLEH = 0xc80
  val INSTRETH = 0xc82

  def MHPMCOUNTER(index: Int): Int = 0xb00 + index
  def MHPMCOUNTERH(index: Int): Int = 0xb80 + index
  def HPMCOUNTER(index: Int): Int = 0xc00 + index
  def HPMCOUNTERH(index: Int): Int = 0xc80 + index

  val MHPMCOUNTER3 = MHPMCOUNTER(3)
  val MHPMCOUNTER4 = MHPMCOUNTER(4)
  val MHPMCOUNTER5 = MHPMCOUNTER(5)
  val MHPMCOUNTER3H = MHPMCOUNTERH(3)
  val MHPMCOUNTER4H = MHPMCOUNTERH(4)
  val MHPMCOUNTER5H = MHPMCOUNTERH(5)

  val HPMCOUNTER3 = HPMCOUNTER(3)
  val HPMCOUNTER4 = HPMCOUNTER(4)
  val HPMCOUNTER5 = HPMCOUNTER(5)
  val HPMCOUNTER3H = HPMCOUNTERH(3)
  val HPMCOUNTER4H = HPMCOUNTERH(4)
  val HPMCOUNTER5H = HPMCOUNTERH(5)

  private val machineLow = Seq(
    MCYCLE,
    MINSTRET,
    MHPMCOUNTER3,
    MHPMCOUNTER4,
    MHPMCOUNTER5
  )

  private val machineHigh = Seq(
    MCYCLEH,
    MINSTRETH,
    MHPMCOUNTER3H,
    MHPMCOUNTER4H,
    MHPMCOUNTER5H
  )

  private val userLow = Seq(
    CYCLE,
    INSTRET,
    HPMCOUNTER3,
    HPMCOUNTER4,
    HPMCOUNTER5
  )

  private val userHigh = Seq(
    CYCLEH,
    INSTRETH,
    HPMCOUNTER3H,
    HPMCOUNTER4H,
    HPMCOUNTER5H
  )

  def all(xlen: Int): Seq[Int] = {
    require(xlen == 32 || xlen == 64, s"Unsupported xlen for counters: $xlen")
    if (xlen == 32) {
      machineLow ++ machineHigh ++ userLow ++ userHigh
    } else {
      machineLow ++ userLow
    }
  }
}

class CounterCSRIO extends Bundle {
  val cycleTick = Input(Bool())
  val instretTick = Input(Bool())
  val branchRetiredTick = Input(Bool())
  val branchMissTick = Input(Bool())
  val hazardStallTick = Input(Bool())
}

/** Zicntr + fixed HPM counters.
  *
  * Implemented counters:
  *   - mcycle/cycle
  *   - minstret/instret
  *   - mhpmcounter3/hpmcounter3: branches retired
  *   - mhpmcounter4/hpmcounter4: branch misses
  *   - mhpmcounter5/hpmcounter5: cycles stalled due to hazards
  */
class CounterCSR(xlen: Int)(implicit p: Parameters) extends LazyModule {
  val node = CSRSlaveNode(
    Seq(CSRSlaveParameters(CounterCSRAddrs.all(xlen), name = "counter_csr"))
  )

  lazy val module = new CounterCSRImp(this, xlen)
}

class CounterCSRImp(outer: CounterCSR, xlen: Int) extends LazyModuleImp(outer) {
  private val (port, edge) = outer.node.in.head
  private val params = edge.params

  val io = IO(new CounterCSRIO)

  private case class CounterAddressMap(
      machineLow: Int,
      machineHigh: Int,
      userLow: Int,
      userHigh: Int
  )

  private val addressMap = Seq(
    CounterAddressMap(
      CounterCSRAddrs.MCYCLE,
      CounterCSRAddrs.MCYCLEH,
      CounterCSRAddrs.CYCLE,
      CounterCSRAddrs.CYCLEH
    ),
    CounterAddressMap(
      CounterCSRAddrs.MINSTRET,
      CounterCSRAddrs.MINSTRETH,
      CounterCSRAddrs.INSTRET,
      CounterCSRAddrs.INSTRETH
    ),
    CounterAddressMap(
      CounterCSRAddrs.MHPMCOUNTER3,
      CounterCSRAddrs.MHPMCOUNTER3H,
      CounterCSRAddrs.HPMCOUNTER3,
      CounterCSRAddrs.HPMCOUNTER3H
    ),
    CounterAddressMap(
      CounterCSRAddrs.MHPMCOUNTER4,
      CounterCSRAddrs.MHPMCOUNTER4H,
      CounterCSRAddrs.HPMCOUNTER4,
      CounterCSRAddrs.HPMCOUNTER4H
    ),
    CounterAddressMap(
      CounterCSRAddrs.MHPMCOUNTER5,
      CounterCSRAddrs.MHPMCOUNTER5H,
      CounterCSRAddrs.HPMCOUNTER5,
      CounterCSRAddrs.HPMCOUNTER5H
    )
  )

  private val counters = RegInit(VecInit(Seq.fill(addressMap.length)(0.U(64.W))))

  private val ticks = Seq(
    io.cycleTick,
    io.instretTick,
    io.branchRetiredTick,
    io.branchMissTick,
    io.hazardStallTick
  )
  require(
    ticks.length == addressMap.length,
    "Counter tick wiring must match counter address map"
  )

  private def readLow(value: UInt): UInt = {
    if (xlen == 64) {
      value(63, 0)
    } else {
      Cat(0.U((params.dataBits - 32).W), value(31, 0))
    }
  }

  private def readHigh(value: UInt): UInt = {
    Cat(0.U((params.dataBits - 32).W), value(63, 32))
  }

  private val addr = port.m2s.addr

  private val readCases = counters.zip(addressMap).flatMap {
    case (counter, map) =>
      val lowCases = Seq(
        (addr === map.machineLow.U) -> readLow(counter),
        (addr === map.userLow.U) -> readLow(counter)
      )

      if (xlen == 32) {
        lowCases ++ Seq(
          (addr === map.machineHigh.U) -> readHigh(counter),
          (addr === map.userHigh.U) -> readHigh(counter)
        )
      } else {
        lowCases
      }
  }

  port.s2m.rdata := MuxCase(0.U(params.dataBits.W), readCases)
  port.s2m.hit := readCases.map(_._1).reduce(_ || _)

  counters.zip(ticks).foreach { case (counter, tick) =>
    when(tick) {
      counter := counter + 1.U
    }
  }

  when(port.m2s.wen) {
    val wdataLow = port.m2s.wdata(31, 0)

    counters.zip(addressMap).foreach { case (counter, map) =>
      when(addr === map.machineLow.U) {
        if (xlen == 32) {
          counter := Cat(counter(63, 32), wdataLow)
        } else {
          counter := port.m2s.wdata(63, 0)
        }
      }

      if (xlen == 32) {
        when(addr === map.machineHigh.U) {
          counter := Cat(wdataLow, counter(31, 0))
        }
      }
    }
  }
}
