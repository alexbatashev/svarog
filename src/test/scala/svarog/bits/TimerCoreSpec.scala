package svarog.bits

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class TimerCoreSpec extends AnyFunSpec with ChiselSim {
  describe("TimerCore") {
    it("should auto-increment mtime every cycle") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        val t0 = dut.io.mtime.peek().litValue
        dut.clock.step(100)
        val t1 = dut.io.mtime.peek().litValue

        assert(t1 == t0 + 100, s"Expected mtime to increment by 100, got ${t1 - t0}")
      }
    }

    it("should write mtime low 32 bits via request interface") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        // Read initial mtime
        val initialMtime = dut.io.mtime.peek().litValue

        // Write mtime low = 0xDEADBEEF
        dut.io.write.valid.poke(true)
        dut.io.write.bits.addr.poke(0x0)
        dut.io.write.bits.data.poke(BigInt("DEADBEEF", 16))
        dut.io.write.bits.isLow.poke(true)
        dut.io.write.bits.isHigh.poke(false)
        dut.clock.step(1)

        dut.io.write.ready.expect(true)
        dut.io.write.valid.poke(false)
        dut.clock.step(1)

        // Check that low bits are 0xDEADBEEF (actually +2 due to auto-increment)
        val expectedLow = (BigInt("DEADBEEF", 16) + 2) & 0xFFFFFFFFL
        dut.io.mtime.expect((expectedLow).toLong)
      }
    }

    it("should write mtimecmp full 64 bits via request interface") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        // Write mtimecmp = 0x123456789ABCDEF
        dut.io.write.valid.poke(true)
        dut.io.write.bits.addr.poke(0x8)
        dut.io.write.bits.data.poke(BigInt("123456789ABCDEF", 16))
        dut.io.write.bits.isLow.poke(false)
        dut.io.write.bits.isHigh.poke(false)
        dut.clock.step(1)

        dut.io.write.ready.expect(true)
        dut.io.write.valid.poke(false)
        dut.clock.step(1)

        dut.io.mtimecmp.expect(BigInt("123456789ABCDEF", 16))
      }
    }

    it("should read mtime via request interface") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        // Step a few cycles so mtime has a known value
        dut.clock.step(10)

        // Read mtime
        dut.io.read.valid.poke(true)
        dut.io.read.bits.addr.poke(0x0)
        dut.io.readData.ready.poke(true)
        dut.clock.step(1)

        dut.io.read.ready.expect(true)
        dut.io.readData.valid.expect(true)

        val readValue = dut.io.readData.bits.peek().litValue
        // mtime should be around 11 (10 initial steps + 1 read step)
        assert(readValue >= 10 && readValue <= 12, s"Expected mtime around 11, got $readValue")
      }
    }

    it("should read mtimecmp via request interface") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        // Write mtimecmp first
        dut.io.write.valid.poke(true)
        dut.io.write.bits.addr.poke(0x8)
        dut.io.write.bits.data.poke(12345)
        dut.io.write.bits.isLow.poke(false)
        dut.io.write.bits.isHigh.poke(false)
        dut.clock.step(1)

        dut.io.write.valid.poke(false)

        // Read mtimecmp
        dut.io.read.valid.poke(true)
        dut.io.read.bits.addr.poke(0x8)
        dut.io.readData.ready.poke(true)
        dut.clock.step(1)

        dut.io.read.ready.expect(true)
        dut.io.readData.valid.expect(true)
        dut.io.readData.bits.expect(12345)
      }
    }

    it("should assert interrupt when mtime >= mtimecmp") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        // Set mtimecmp = 20
        dut.io.write.valid.poke(true)
        dut.io.write.bits.addr.poke(0x8)
        dut.io.write.bits.data.poke(20)
        dut.io.write.bits.isLow.poke(false)
        dut.io.write.bits.isHigh.poke(false)
        dut.clock.step(1)

        dut.io.write.valid.poke(false)

        // Initially no interrupt (mtime is around 1)
        dut.io.timerInterrupt.expect(false)

        // Step until mtime >= 20
        dut.clock.step(19)

        // Now mtime should be >= 20, interrupt should be active
        dut.io.timerInterrupt.expect(true)
      }
    }

    it("should clear interrupt when mtimecmp is updated to > mtime") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        // Set mtimecmp = 5
        dut.io.write.valid.poke(true)
        dut.io.write.bits.addr.poke(0x8)
        dut.io.write.bits.data.poke(5)
        dut.io.write.bits.isLow.poke(false)
        dut.io.write.bits.isHigh.poke(false)
        dut.clock.step(1)

        dut.io.write.valid.poke(false)

        // Wait for interrupt
        dut.clock.step(10)
        dut.io.timerInterrupt.expect(true)

        // Update mtimecmp = 1000 (> mtime)
        dut.io.write.valid.poke(true)
        dut.io.write.bits.addr.poke(0x8)
        dut.io.write.bits.data.poke(1000)
        dut.clock.step(1)

        // Interrupt should clear
        dut.io.timerInterrupt.expect(false)
      }
    }

    it("should handle 64-bit mtime overflow correctly") {
      simulate(new TimerCore) { dut =>
        dut.io.write.valid.poke(false)
        dut.io.read.valid.poke(false)
        dut.io.readData.ready.poke(true)

        // Set mtime to near max: 0xFFFFFFFFFFFFFFFE
        val nearMaxHigh = BigInt("FFFFFFFF", 16).toLong
        val nearMaxLow = BigInt("FFFFFFFE", 16).toLong

        // Write high bits first
        dut.io.write.valid.poke(true)
        dut.io.write.bits.addr.poke(0x0)
        dut.io.write.bits.data.poke(nearMaxHigh)
        dut.io.write.bits.isHigh.poke(true)
        dut.io.write.bits.isLow.poke(false)
        dut.clock.step(1)

        // Write low bits
        dut.io.write.bits.data.poke(nearMaxLow)
        dut.io.write.bits.isHigh.poke(false)
        dut.io.write.bits.isLow.poke(true)
        dut.clock.step(1)

        dut.io.write.valid.poke(false)

        // Step 3 cycles (should overflow)
        dut.clock.step(3)

        // Read mtime - should have wrapped around
        // After write low, mtime was 0xFFFFFFFFFFFFFFFF, then +3 steps = 0x2
        val currentMtime = dut.io.mtime.peek().litValue
        assert(currentMtime == 2, s"Expected mtime to be 2 after overflow, got $currentMtime")
      }
    }
  }
}
