package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RTCSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RTC"

  it should "transfer rtc clock ticks into the system clock domain" in {
    simulate(new Module {
      val io = IO(new Bundle {
        val rtcClock = Input(Clock())
        val time = Output(UInt(64.W))
      })
      val rtc = Module(new RTC)
      rtc.clk := clock
      rtc.reset := reset
      rtc.io.rtcClock := io.rtcClock
      io.time := rtc.io.time
    }) { dut =>
      var totalRtcTicks = 0

      def stepRtc(n: Int): Unit = {
        for (_ <- 0 until n) {
          dut.io.rtcClock.step(1)
          totalRtcTicks += 1
        }
      }

      def stepSys(n: Int): Unit = {
        for (_ <- 0 until n) {
          dut.clock.step(1)
        }
      }

      dut.reset.poke(true.B)
      stepSys(2)
      stepRtc(2)
      dut.reset.poke(false.B)
      stepSys(1)
      stepRtc(1)

      val start = dut.io.time.peek().litValue
      for (_ <- 0 until 5) {
        stepSys(1)
        dut.io.time.peek().litValue shouldBe start
      }

      def runPhase(rtcPerSys: Int, sysSteps: Int): Unit = {
        var last = dut.io.time.peek().litValue
        var sawIncrement = false
        for (_ <- 0 until sysSteps) {
          stepRtc(rtcPerSys)
          stepSys(1)
          val now = dut.io.time.peek().litValue
          now should be >= last
          now should be <= BigInt(totalRtcTicks)
          if (now > last) {
            sawIncrement = true
          }
          last = now
        }
        sawIncrement shouldBe true
      }

      runPhase(rtcPerSys = 1, sysSteps = 20)
      runPhase(rtcPerSys = 3, sysSteps = 20)
    }
  }
}
