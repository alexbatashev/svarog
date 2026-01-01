package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemoryUtilsSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "asLE"

  class LittleEndianRound extends Module {
    val io = IO(new Bundle {
      val a = Input(UInt(32.W))
      val b = Output(UInt(32.W))
    })

    val v = asLE(io.a)
    io.b := v.asUInt
  }

  it should "correctly produce the answer" in {
    simulate(new LittleEndianRound) { dut =>
      dut.io.a.poke("xdeadbeef".U)
      dut.io.b.expect("xdeadbeef".U)
    }
  }
}
