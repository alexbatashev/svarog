package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SimpleMultiplierSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "SimpleMultiplier"

  private val xlen = 32

  private case class MulVector(
      multiplicant: BigInt,
      multiplier: BigInt,
      op: MulOp.Type,
      expected: BigInt
  )

  // Helper to convert to signed 32-bit value
  private def toSigned32(value: BigInt): BigInt = {
    if (value >= (1L << 31)) value - (1L << 32)
    else value
  }

  // Helper to convert to unsigned 32-bit value
  private def toUnsigned32(value: BigInt): BigInt = {
    value & 0xffffffffL
  }

  private def runSimulation(dut: AbstractMultiplier, vectors: Seq[MulVector]): Unit = {
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)

      for (vector <- vectors) {
        dut.io.inp.bits.multiplicant.poke(vector.multiplicant.U)
        dut.io.inp.bits.multiplier.poke(vector.multiplier.U)
        dut.io.inp.bits.op.poke(vector.op)
        dut.io.inp.valid.poke(true.B)

        dut.clock.step(4)

        dut.io.result.valid.expect(true.B)
        dut.io.result.bits.expect(vector.expected.U)

        dut.clock.step(1)
      }
  }

  it should "compute MUL (lower 32 bits) correctly" in {
    val vectors = Seq(
      MulVector(
        multiplicant = 0,
        multiplier = 0,
        op = MulOp.MUL,
        expected = 0
      ),
      MulVector(
        multiplicant = 1,
        multiplier = 1,
        op = MulOp.MUL,
        expected = 1
      ),
      MulVector(
        multiplicant = 5,
        multiplier = 7,
        op = MulOp.MUL,
        expected = 35
      ),
      MulVector(
        multiplicant = BigInt("FFFFFFFF", 16),
        multiplier = BigInt("FFFFFFFF", 16),
        op = MulOp.MUL,
        expected = 1 // Lower 32 bits of 0xFFFFFFFE00000001
      ),
      MulVector(
        multiplicant = BigInt("12345678", 16),
        multiplier = BigInt("9ABCDEF0", 16),
        op = MulOp.MUL,
        expected = BigInt("242d2080", 16) // Lower 32 bits
      ),
      MulVector(
        multiplicant = BigInt("80000000", 16),
        multiplier = 2,
        op = MulOp.MUL,
        expected = 0 // Overflow: 0x100000000, lower bits are 0
      )
    )

    simulate(new SimpleMultiplier(xlen, latency = 4)) { dut =>
      runSimulation(dut, vectors)
    }
  }

  it should "compute MULH (upper 32 bits, signed × signed) correctly" in {
    val vectors = Seq(
      MulVector(
        multiplicant = 0,
        multiplier = 0,
        op = MulOp.MULH,
        expected = 0
      ),
      MulVector(
        multiplicant = 1,
        multiplier = 1,
        op = MulOp.MULH,
        expected = 0
      ),
      MulVector(
        multiplicant = BigInt("FFFFFFFF", 16), // -1 in signed
        multiplier = BigInt("FFFFFFFF", 16), // -1 in signed
        op = MulOp.MULH,
        expected = 0 // (-1) × (-1) = 1, upper 32 bits = 0
      ),
      MulVector(
        multiplicant = BigInt("80000000", 16), // -2147483648 (most negative)
        multiplier = 2,
        op = MulOp.MULH,
        expected =
          BigInt("FFFFFFFF", 16) // -1 (sign extension of negative result)
      ),
      MulVector(
        multiplicant = BigInt("7FFFFFFF", 16), // 2147483647 (most positive)
        multiplier = 2,
        op = MulOp.MULH,
        expected = 0 // Result: 4294967294, upper bits = 0
      ),
      MulVector(
        multiplicant = BigInt("80000000", 16), // -2147483648
        multiplier = BigInt("80000000", 16), // -2147483648
        op = MulOp.MULH,
        expected = BigInt("40000000", 16) // Positive overflow result
      ),
      MulVector(
        multiplicant = BigInt("FFFFFFFF", 16), // -1
        multiplier = BigInt("7FFFFFFF", 16), // 2147483647
        op = MulOp.MULH,
        expected =
          BigInt("FFFFFFFF", 16) // Negative result, all 1s in upper bits
      )
    )

    simulate(new SimpleMultiplier(xlen, latency = 4)) { dut =>
      runSimulation(dut, vectors)
    }
  }

  it should "compute MULHSU (upper 32 bits, signed × unsigned) correctly" in {
    val vectors = Seq(
      MulVector(
        multiplicant = 0,
        multiplier = 0,
        op = MulOp.MULHSU,
        expected = 0
      ),
      MulVector(
        multiplicant = 1,
        multiplier = 1,
        op = MulOp.MULHSU,
        expected = 0
      ),
      MulVector(
        multiplicant = BigInt("FFFFFFFF", 16), // -1 in signed
        multiplier = BigInt("FFFFFFFF", 16), // 4294967295 in unsigned
        op = MulOp.MULHSU,
        expected = BigInt("FFFFFFFF", 16) // Negative result
      ),
      MulVector(
        multiplicant = BigInt("80000000", 16), // -2147483648
        multiplier = 2,
        op = MulOp.MULHSU,
        expected =
          BigInt("FFFFFFFF", 16) // Negative result (sign bit propagates)
      ),
      MulVector(
        multiplicant = BigInt("7FFFFFFF", 16), // 2147483647 (positive)
        multiplier = BigInt("FFFFFFFF", 16), // 4294967295 (unsigned)
        op = MulOp.MULHSU,
        expected = BigInt("7FFFFFFE", 16) // Positive result
      ),
      MulVector(
        multiplicant = BigInt("FFFFFFFF", 16), // -1
        multiplier = BigInt("80000000", 16), // 2147483648 (unsigned)
        op = MulOp.MULHSU,
        expected = BigInt("FFFFFFFF", 16) // Negative result
      )
    )

    simulate(new SimpleMultiplier(xlen, latency = 4)) { dut =>
      runSimulation(dut, vectors)
    }
  }

  it should "compute MULHU (upper 32 bits, unsigned × unsigned) correctly" in {
    val vectors = Seq(
      MulVector(
        multiplicant = 0,
        multiplier = 0,
        op = MulOp.MULHU,
        expected = 0
      ),
      MulVector(
        multiplicant = 1,
        multiplier = 1,
        op = MulOp.MULHU,
        expected = 0
      ),
      MulVector(
        multiplicant = BigInt("FFFFFFFF", 16),
        multiplier = BigInt("FFFFFFFF", 16),
        op = MulOp.MULHU,
        expected = BigInt("FFFFFFFE", 16) // Upper 32 bits of max × max
      ),
      MulVector(
        multiplicant = BigInt("80000000", 16),
        multiplier = 2,
        op = MulOp.MULHU,
        expected = 1 // 0x100000000, upper bits = 1
      ),
      MulVector(
        multiplicant = BigInt("12345678", 16),
        multiplier = BigInt("9ABCDEF0", 16),
        op = MulOp.MULHU,
        expected = BigInt("0B00EA4E", 16) // Upper 32 bits
      ),
      MulVector(
        multiplicant = BigInt("7FFFFFFF", 16),
        multiplier = BigInt("7FFFFFFF", 16),
        op = MulOp.MULHU,
        expected = BigInt("3FFFFFFF", 16) // Positive × positive
      )
    )

    simulate(new SimpleMultiplier(xlen, latency = 4)) { dut =>
      runSimulation(dut, vectors)
    }
  }

  it should "handle valid/ready handshake correctly" in {
    simulate(new SimpleMultiplier(xlen, latency = 4)) { dut =>
      // Module should be ready initially (not busy)
      dut.io.inp.ready.expect(true.B)

      // When input is not valid, output should not be valid
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)
      dut.io.result.valid.expect(false.B)

      // When input is valid, initiate transaction
      dut.io.inp.bits.multiplicant.poke(10.U)
      dut.io.inp.bits.multiplier.poke(20.U)
      dut.io.inp.bits.op.poke(MulOp.MUL)
      dut.io.inp.valid.poke(true.B)
      dut.clock.step(1)

      // After accepting, multiplier is busy (blocking design)
      dut.io.inp.ready.expect(false.B)
      dut.io.inp.valid.poke(false.B)

      // Wait for result (at cycle latency-1, result becomes valid)
      dut.clock.step(2)
      dut.io.result.valid.expect(false.B) // Not yet
      dut.clock.step(1) // One more cycle to reach latency-1
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(200.U)

      // After counter wraps, ready again on next cycle
      dut.clock.step(1)
      dut.io.inp.ready.expect(true.B)
      dut.io.result.valid.expect(false.B)
    }
  }

  it should "process pipelined operations with correct latency" in {
    simulate(new SimpleMultiplier(xlen, latency = 3)) { dut =>
      // Test that result is available after the correct latency
      dut.io.inp.bits.multiplicant.poke(42.U)
      dut.io.inp.bits.multiplier.poke(13.U)
      dut.io.inp.bits.op.poke(MulOp.MUL)
      dut.io.inp.valid.poke(true.B)

      // Result should not be valid immediately
      dut.io.result.valid.expect(false.B)

      dut.clock.step(1)
      dut.io.inp.valid.poke(false.B)

      // After 2 more cycles (total latency = 3), result should be valid
      dut.clock.step(2)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(546.U)

      // Test that we can accept another operation after busy clears
      dut.clock.step(1)
      dut.io.inp.ready.expect(true.B)

      dut.io.inp.bits.multiplicant.poke(100.U)
      dut.io.inp.bits.multiplier.poke(100.U)
      dut.io.inp.bits.op.poke(MulOp.MUL)
      dut.io.inp.valid.poke(true.B)

      dut.clock.step(3)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(10000.U)
    }
  }

  it should "match RISC-V Zmmul extension edge cases" in {
    val vectors = Seq(
      // Zero × anything = 0
      MulVector(0, BigInt("FFFFFFFF", 16), MulOp.MUL, 0),
      MulVector(0, BigInt("FFFFFFFF", 16), MulOp.MULH, 0),
      MulVector(0, BigInt("FFFFFFFF", 16), MulOp.MULHSU, 0),
      MulVector(0, BigInt("FFFFFFFF", 16), MulOp.MULHU, 0),

      // One × value = value (for MUL)
      MulVector(1, BigInt("12345678", 16), MulOp.MUL, BigInt("12345678", 16)),

      // Negative × negative = positive (MULH)
      MulVector(
        BigInt("FFFFFFFE", 16),
        BigInt("FFFFFFFE", 16),
        MulOp.MULH,
        0
      ), // (-2) × (-2) = 4

      // Large unsigned multiplication
      MulVector(
        BigInt("FFFFFFFF", 16),
        2,
        MulOp.MULHU,
        1
      ), // (2^32-1) × 2 upper bits

      // Sign bit handling in MULHSU
      MulVector(
        BigInt("80000000", 16),
        1,
        MulOp.MULHSU,
        BigInt("FFFFFFFF", 16)
      ) // -2^31 × 1 (unsigned)
    )

    simulate(new SimpleMultiplier(xlen, latency = 4)) { dut =>
      runSimulation(dut, vectors)
    }
  }
}
