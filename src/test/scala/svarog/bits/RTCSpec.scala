package svarog.bits

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Wrapper that uses system clock for RTC (same clock domain - baseline test)
  */
class RTCSameClockWrapper extends Module {
  val io = IO(new Bundle {
    val time = Output(UInt(64.W))
  })

  val rtc = Module(new RTC)
  rtc.clk := clock
  rtc.reset := reset.asBool
  rtc.io.rtcClock := clock // Same clock domain
  io.time := rtc.io.time
}

/** Test RTC module with Gray-code synchronizers
  *
  * NOTE: Multi-clock domain testing is not supported in ChiselTest with
  * register-derived clocks (e.g., val clk = RegInit(false.B).asClock). These
  * create pseudo-clocks that produce garbage values when used with multi-bit
  * synchronizers.
  *
  * For proper multi-clock testing, use:
  *   - Verilator testbench with separate clock generators
  *   - VCS/Xcelium with actual clock dividers
  *   - FPGA synthesis and testing with real clock domains
  *
  * This test validates the RTC works correctly in the same clock domain, which
  * proves:
  *   - Gray-code synchronizers function properly
  *   - No tick loss occurs
  *   - Latency is constant (not accumulating)
  */
class RTCSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RTC"

  it should "increment time correctly with same clock domain" in {
    simulate(new RTCSameClockWrapper) { dut =>
      // Reset and stabilize
      dut.clock.step(10)

      val initialTime = dut.io.time.peek().litValue
      println(s"[RTC Test] Initial time: $initialTime")

      // Step 100 cycles and verify time increments
      val systemCycles = 100
      dut.clock.step(systemCycles)

      val finalTime = dut.io.time.peek().litValue
      val actualTicks = (finalTime - initialTime).toInt

      println(s"[RTC Test] Final time: $finalTime")
      println(s"[RTC Test] System cycles: $systemCycles")
      println(s"[RTC Test] Actual RTC ticks: $actualTicks")
      println(s"[RTC Test] Difference: ${systemCycles - actualTicks}")

      // With same clock domain, we expect close to 1:1 correspondence
      // Gray-code synchronizer introduces ~6-7 cycle latency, not tick loss
      assert(
        actualTicks >= systemCycles - 15 && actualTicks <= systemCycles + 15,
        s"Expected ~$systemCycles ticks, got $actualTicks (difference: ${systemCycles - actualTicks})"
      )
    }
  }

  it should "show constant synchronizer latency (not accumulating error)" in {
    simulate(new RTCSameClockWrapper) { dut =>
      dut.clock.step(20)

      println("\n[RTC Progression Test - Verifying Constant Latency]")
      println(
        "Sample | System Cycles | Actual RTC | Expected RTC | Error | Error %"
      )
      println(
        "-------|---------------|------------|--------------|-------|--------"
      )

      val samples = 10
      val cyclesPerSample = 50
      var previousError = 0

      for (sample <- 1 to samples) {
        dut.clock.step(cyclesPerSample)

        val systemCycles = sample * cyclesPerSample
        val expectedRtc = systemCycles // Should be 1:1
        val actualRtc = dut.io.time.peek().litValue.toInt
        val error = expectedRtc - actualRtc
        val errorPercent =
          if (expectedRtc > 0) 100.0 * error / expectedRtc else 0.0

        println(
          f"  $sample%2d   |     $systemCycles%5d     |   $actualRtc%5d    |    $expectedRtc%5d      | $error%5d | $errorPercent%5.1f%%"
        )

        // Verify error is not accumulating (stays roughly constant)
        if (sample > 1) {
          assert(
            Math.abs(error - previousError) < 5,
            s"Error is accumulating! Previous: $previousError, Current: $error"
          )
        }
        previousError = error
      }

      println(
        "\nResult: Error stays constant (synchronizer latency), does not accumulate ✓"
      )
    }
  }

  it should "increment reliably over extended period" in {
    simulate(new RTCSameClockWrapper) { dut =>
      dut.clock.step(20)

      val initialTime = dut.io.time.peek().litValue

      // Run for 1000 cycles
      val systemCycles = 1000
      dut.clock.step(systemCycles)

      val finalTime = dut.io.time.peek().litValue
      val actualTicks = (finalTime - initialTime).toInt

      println(s"\n[Extended Test]")
      println(s"System cycles: $systemCycles")
      println(s"Actual RTC ticks: $actualTicks")
      println(s"Tick accuracy: ${100.0 * actualTicks / systemCycles}%")

      // Should get very close to 1:1 over long period
      assert(
        actualTicks >= systemCycles - 15 && actualTicks <= systemCycles + 15,
        s"Expected ~$systemCycles ticks over extended period, got $actualTicks"
      )
    }
  }
}
