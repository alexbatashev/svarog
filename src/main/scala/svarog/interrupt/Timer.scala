package svarog.interrupt

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources.SimpleDevice

/** Timer module following SiFive CLINT memory layout for timer registers.
  *
  * Memory map (relative to baseAddr):
  *   - 0x0000: mtime      (64-bit, RO) - current time from RTC
  *   - 0x4000: mtimecmp0  (64-bit, RW) - timer compare for hart 0
  *   - 0x4008: mtimecmp1  (64-bit, RW) - timer compare for hart 1
  *   - ...
  *
  * Timer interrupt fires when mtime >= mtimecmp[hart].
  */
class Timer(numHarts: Int, xlen: Int, baseAddr: Long)(implicit p: Parameters) extends LazyModule {
  private val beatBytes = xlen / 8
  // Size must be a power of 2 for TileLink AddressSet mask requirements.
  // We need to cover mtime at 0x0000 and mtimecmp at 0x4000+.
  // Max offset is 0x4000 + numHarts * 8, round up to next power of 2.
  private val minSize = 0x4000 + numHarts * 8
  private val size = 1 << log2Ceil(minSize)

  val device = new SimpleDevice("timer", Seq("riscv,timer0"))
  val node = TLRegisterNode(
    address = Seq(AddressSet(baseAddr, size - 1)),
    device = device,
    beatBytes = beatBytes,
    concurrency = 1
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val time = Input(UInt(64.W))
      val fire = Output(Vec(numHarts, Bool()))
    })

    // mtimecmp registers as separate low/high halves for 32-bit compatibility
    val mtimecmpLo = Seq.fill(numHarts)(RegInit(~0.U(32.W)))
    val mtimecmpHi = Seq.fill(numHarts)(RegInit(~0.U(32.W)))

    // Fire interrupt when time >= mtimecmp
    for (i <- 0 until numHarts) {
      val mtimecmp = Cat(mtimecmpHi(i), mtimecmpLo(i))
      io.fire(i) := io.time >= mtimecmp
    }

    // For 32-bit systems, split 64-bit registers into low/high halves
    if (beatBytes == 4) {
      val mtimeFields = Seq(
        0x0000 -> Seq(RegField.r(32, io.time(31, 0), RegFieldDesc("mtime_lo", "Machine time low"))),
        0x0004 -> Seq(RegField.r(32, io.time(63, 32), RegFieldDesc("mtime_hi", "Machine time high")))
      )

      val mtimecmpFields = (0 until numHarts).flatMap { i =>
        Seq(
          (0x4000 + i * 8) -> Seq(RegField(32, mtimecmpLo(i), RegFieldDesc(s"mtimecmp${i}_lo", s"Timer compare low for hart $i"))),
          (0x4004 + i * 8) -> Seq(RegField(32, mtimecmpHi(i), RegFieldDesc(s"mtimecmp${i}_hi", s"Timer compare high for hart $i")))
        )
      }

      node.regmap((mtimeFields ++ mtimecmpFields): _*)
    } else {
      // 64-bit system: pack lo/hi into single 64-bit word at each offset
      val mtimeRegField = 0x0000 -> Seq(RegField.r(64, io.time, RegFieldDesc("mtime", "Machine time")))
      val mtimecmpRegFields = (0 until numHarts).map { i =>
        (0x4000 + i * 8) -> Seq(
          RegField(32, mtimecmpLo(i), RegFieldDesc(s"mtimecmp${i}_lo", s"Timer compare low for hart $i")),
          RegField(32, mtimecmpHi(i), RegFieldDesc(s"mtimecmp${i}_hi", s"Timer compare high for hart $i"))
        )
      }
      node.regmap((mtimeRegField +: mtimecmpRegFields): _*)
    }
  }
}
