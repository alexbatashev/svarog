package svarog.bits

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class TimerSpec extends AnyFunSpec with ChiselSim {
  describe("TimerWishbone") {

    // Helper to wait for async read completion
    def waitForAck(dut: TimerWishbone, maxCycles: Int = 20): Boolean = {
      var cycles = 0
      while (cycles < maxCycles && !dut.io.ack.peek().litToBoolean) {
        dut.clock.step(1)
        cycles += 1
      }
      dut.io.ack.peek().litToBoolean
    }

    it("should auto-increment mtime every cycle") {
      simulate(new TimerWishbone(baseAddr = 0x02000000L)) { dut =>
        import TimerConstants._

        // Connect timer clock to system clock
        dut.timerClock.poke(dut.clock)

        // Initialize
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.clock.step(1)

        // Read initial mtime low
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(false)
        dut.io.addr.poke(0x02000000L + MTIME_LOW_OFFSET)
        dut.io.sel.foreach(_.poke(true))
        dut.clock.step(1)

        // Wait for async read to complete
        assert(waitForAck(dut), "Read did not complete")
        val t0 = dut.io.dataToMaster.peek().litValue.toLong

        // Deactivate bus and step 100 cycles
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.clock.step(100)

        // Read mtime again
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.addr.poke(0x02000000L + MTIME_LOW_OFFSET)
        dut.clock.step(1)

        assert(waitForAck(dut), "Read did not complete")
        val t1 = dut.io.dataToMaster.peek().litValue.toLong

        // Verify increment (should be around 100, give or take a few cycles for CDC)
        val increment = t1 - t0
        assert(increment >= 95 && increment <= 110, s"Expected mtime to increment by ~100, but got $increment")
      }
    }

    it("should write and read 64-bit mtime") {
      simulate(new TimerWishbone(baseAddr = 0x02000000L)) { dut =>
        import TimerConstants._

        // Connect timer clock to system clock
        dut.timerClock.poke(dut.clock)

        // Initialize
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.sel.foreach(_.poke(true))

        // Write mtime high first (0xcafe1234)
        dut.io.addr.poke(0x02000000L + MTIME_HIGH_OFFSET)
        dut.io.dataToSlave.poke(BigInt("cafe1234", 16).toLong)
        dut.clock.step(1)

        dut.io.ack.expect(true)  // Writes should get immediate ack

        // Write mtime low (0xdeadbeef)
        dut.io.addr.poke(0x02000000L + MTIME_LOW_OFFSET)
        dut.io.dataToSlave.poke(BigInt("deadbeef", 16).toLong)
        dut.clock.step(1)

        dut.io.ack.expect(true)

        // Wait a bit for writes to propagate through CDC
        dut.io.cycleActive.poke(false)
        dut.clock.step(10)

        // Read back mtime low
        dut.io.cycleActive.poke(true)
        dut.io.writeEnable.poke(false)
        dut.io.addr.poke(0x02000000L + MTIME_LOW_OFFSET)
        dut.clock.step(1)

        assert(waitForAck(dut), "Read did not complete")
        val readLow = dut.io.dataToMaster.peek().litValue
        // Allow some auto-increment tolerance
        val expectedLow = BigInt("deadbeef", 16)
        assert((readLow - expectedLow).abs < 20, s"Expected mtime low ~${expectedLow.toString(16)}, got ${readLow.toString(16)}")

        // Read back mtime high
        dut.io.cycleActive.poke(false)
        dut.clock.step(2)
        dut.io.cycleActive.poke(true)
        dut.io.addr.poke(0x02000000L + MTIME_HIGH_OFFSET)
        dut.clock.step(1)

        assert(waitForAck(dut), "Read did not complete")
        dut.io.dataToMaster.expect(BigInt("cafe1234", 16).toLong)
      }
    }

    it("should write and read mtimecmp") {
      simulate(new TimerWishbone(baseAddr = 0x02000000L)) { dut =>
        import TimerConstants._

        // Connect timer clock to system clock
        dut.timerClock.poke(dut.clock)

        // Initialize
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.sel.foreach(_.poke(true))

        // Write mtimecmp low (0x9abcdef)
        dut.io.addr.poke(0x02000000L + MTIMECMP_LOW_OFFSET)
        dut.io.dataToSlave.poke(BigInt("9abcdef", 16).toLong)
        dut.clock.step(1)

        dut.io.ack.expect(true)

        // Write mtimecmp high (0x1234567)
        dut.io.addr.poke(0x02000000L + MTIMECMP_HIGH_OFFSET)
        dut.io.dataToSlave.poke(BigInt("1234567", 16).toLong)
        dut.clock.step(1)

        dut.io.ack.expect(true)

        // Wait for writes to propagate
        dut.io.cycleActive.poke(false)
        dut.clock.step(10)

        // Read back mtimecmp low
        dut.io.cycleActive.poke(true)
        dut.io.writeEnable.poke(false)
        dut.io.addr.poke(0x02000000L + MTIMECMP_LOW_OFFSET)
        dut.clock.step(1)

        assert(waitForAck(dut), "Read did not complete")
        dut.io.dataToMaster.expect(BigInt("9abcdef", 16).toLong)

        // Read back mtimecmp high
        dut.io.cycleActive.poke(false)
        dut.clock.step(2)
        dut.io.cycleActive.poke(true)
        dut.io.addr.poke(0x02000000L + MTIMECMP_HIGH_OFFSET)
        dut.clock.step(1)

        assert(waitForAck(dut), "Read did not complete")
        dut.io.dataToMaster.expect(BigInt("1234567", 16).toLong)
      }
    }

    it("should assert interrupt when mtime >= mtimecmp") {
      simulate(new TimerWishbone(baseAddr = 0x02000000L)) { dut =>
        import TimerConstants._

        // Connect timer clock to system clock
        dut.timerClock.poke(dut.clock)

        // Initialize
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.sel.foreach(_.poke(true))

        // Set mtimecmp = 100
        dut.io.addr.poke(0x02000000L + MTIMECMP_LOW_OFFSET)
        dut.io.dataToSlave.poke(100)
        dut.clock.step(1)

        // Set mtimecmp high to 0
        dut.io.addr.poke(0x02000000L + MTIMECMP_HIGH_OFFSET)
        dut.io.dataToSlave.poke(0)
        dut.clock.step(1)

        // Initially no interrupt
        dut.timerInterrupt.expect(false)

        // Deactivate bus and wait for mtime to reach ~95
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.clock.step(90)

        dut.timerInterrupt.expect(false)

        // Step more cycles - should trigger interrupt when mtime >= 100
        // Note: interrupt is synchronized with 2-FF so may take a few extra cycles
        dut.clock.step(15)

        // Now mtime >= 100, interrupt should be active (accounting for sync delay)
        dut.timerInterrupt.expect(true)
      }
    }

    it("should clear interrupt when mtimecmp > mtime") {
      simulate(new TimerWishbone(baseAddr = 0x02000000L)) { dut =>
        import TimerConstants._

        // Connect timer clock to system clock
        dut.timerClock.poke(dut.clock)

        // Initialize
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.sel.foreach(_.poke(true))

        // Set mtimecmp = 10
        dut.io.addr.poke(0x02000000L + MTIMECMP_LOW_OFFSET)
        dut.io.dataToSlave.poke(10)
        dut.clock.step(1)

        dut.io.addr.poke(0x02000000L + MTIMECMP_HIGH_OFFSET)
        dut.io.dataToSlave.poke(0)
        dut.clock.step(1)

        // Deactivate bus and wait for interrupt
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.clock.step(25)

        // Should have interrupt by now (with sync delay)
        dut.timerInterrupt.expect(true)

        // Update mtimecmp = 1000 (> current mtime)
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.addr.poke(0x02000000L + MTIMECMP_LOW_OFFSET)
        dut.io.dataToSlave.poke(1000)
        dut.clock.step(1)

        // Wait for write to propagate and interrupt to clear (sync delay)
        dut.io.cycleActive.poke(false)
        dut.clock.step(10)

        // Interrupt should clear
        dut.timerInterrupt.expect(false)
      }
    }

    it("should respond to all register addresses") {
      simulate(new TimerWishbone(baseAddr = 0x02000000L)) { dut =>
        import TimerConstants._

        // Connect timer clock to system clock
        dut.timerClock.poke(dut.clock)

        // Test that all 4 register addresses respond
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(false)
        dut.io.sel.foreach(_.poke(true))

        val addresses = Seq(
          0x02000000L + MTIME_LOW_OFFSET,
          0x02000000L + MTIME_HIGH_OFFSET,
          0x02000000L + MTIMECMP_LOW_OFFSET,
          0x02000000L + MTIMECMP_HIGH_OFFSET
        )

        for (addr <- addresses) {
          dut.io.addr.poke(addr)
          dut.clock.step(1)
          assert(waitForAck(dut), s"Read did not complete for address 0x${addr.toHexString}")
        }
      }
    }

    it("should handle 64-bit mtime overflow correctly") {
      simulate(new TimerWishbone(baseAddr = 0x02000000L)) { dut =>
        import TimerConstants._

        // Connect timer clock to system clock
        dut.timerClock.poke(dut.clock)

        // Initialize
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.sel.foreach(_.poke(true))

        // Write high bits first
        val nearMaxHigh = BigInt("FFFFFFFF", 16).toLong
        dut.io.addr.poke(0x02000000L + MTIME_HIGH_OFFSET)
        dut.io.dataToSlave.poke(nearMaxHigh)
        dut.clock.step(1)

        // Write low bits
        val nearMaxLow = BigInt("FFFFFFFE", 16).toLong
        dut.io.addr.poke(0x02000000L + MTIME_LOW_OFFSET)
        dut.io.dataToSlave.poke(nearMaxLow)
        dut.clock.step(1)

        // Wait for writes to propagate
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.clock.step(5)

        // Read mtime low - should have overflowed
        dut.io.cycleActive.poke(true)
        dut.io.writeEnable.poke(false)
        dut.io.addr.poke(0x02000000L + MTIME_LOW_OFFSET)
        dut.clock.step(1)

        assert(waitForAck(dut), "Read did not complete")
        val readLow = dut.io.dataToMaster.peek().litValue
        // Should be small value after overflow (allowing for CDC delays and auto-increment)
        assert(readLow < 20, s"Expected mtime to be small after overflow, got $readLow")

        // Read high bits - should be 0 after overflow
        dut.io.cycleActive.poke(false)
        dut.clock.step(2)
        dut.io.cycleActive.poke(true)
        dut.io.addr.poke(0x02000000L + MTIME_HIGH_OFFSET)
        dut.clock.step(1)

        assert(waitForAck(dut), "Read did not complete")
        dut.io.dataToMaster.expect(0)
      }
    }
  }
}
