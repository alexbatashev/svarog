package svarog.interrupt

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources.SimpleDevice

/** MSIP (Machine Software Interrupt Pending) module
  *
  * Memory map (relative to baseAddr):
  *   - 0x0000: msip[0] (32-bit, only bit 0 used) - software interrupt for hart
  *     0
  *   - 0x0004: msip[1] (32-bit, only bit 0 used) - software interrupt for hart
  *     1
  *   - ...
  *
  * Writing 1 to bit 0 of msip[i] sets the software interrupt pending bit for
  * hart i. Writing 0 clears it.
  */
class MSIP(numHarts: Int, xlen: Int, baseAddr: Long)(implicit p: Parameters)
    extends LazyModule {

  private val beatBytes = xlen / 8
  // Size: 4 bytes per hart for MSIP registers, up to 0x3FFF to cover the MSIP region
  private val size = 0x4000

  val device = new SimpleDevice("msip", Seq("riscv,clint0"))
  val node = TLRegisterNode(
    address = Seq(AddressSet(baseAddr, size - 1)),
    device = device,
    beatBytes = beatBytes,
    concurrency = 1
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val fire = Output(Vec(numHarts, Bool()))
    })

    // MSIP registers - one per hart, only bit 0 is used
    val msip = Seq.fill(numHarts)(RegInit(false.B))

    // Memory map: hart_id * 4 -> 32-bit register (only bit 0 matters)
    val fields = (0 until numHarts).map { i =>
      (i * 4) -> Seq(
        RegField(
          1,
          msip(i),
          RegFieldDesc(s"msip$i", s"Software interrupt pending for hart $i")
        )
      )
    }

    node.regmap(fields: _*)

    // Output software interrupt signals
    (0 until numHarts).foreach { i =>
      io.fire(i) := msip(i)
    }
  }
}
