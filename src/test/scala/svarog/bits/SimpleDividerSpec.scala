package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SimpleDividerSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "SimpleDivider"

  private val xlen = 32

  private case class DivVector(
      dividend: BigInt,
      divisor: BigInt,
      op: DivOp.Type,
      expected: BigInt
  )

  it should "compute DIV (signed division) correctly" in {
    val vectors = Seq(
      DivVector(
        dividend = 20,
        divisor = 6,
        op = DivOp.DIV,
        expected = 3
      ),
      DivVector(
        dividend = 20,
        divisor = 1,
        op = DivOp.DIV,
        expected = 20
      ),
      DivVector(
        dividend = 0,
        divisor = 5,
        op = DivOp.DIV,
        expected = 0
      ),
      // Negative dividend
      DivVector(
        dividend = BigInt("FFFFFFEC", 16), // -20
        divisor = 6,
        op = DivOp.DIV,
        expected = BigInt("FFFFFFFD", 16) // -3
      ),
      // Negative divisor
      DivVector(
        dividend = 20,
        divisor = BigInt("FFFFFFFA", 16), // -6
        op = DivOp.DIV,
        expected = BigInt("FFFFFFFD", 16) // -3
      ),
      // Both negative
      DivVector(
        dividend = BigInt("FFFFFFEC", 16), // -20
        divisor = BigInt("FFFFFFFA", 16), // -6
        op = DivOp.DIV,
        expected = 3
      ),
      // Division by zero: result = -1
      DivVector(
        dividend = 42,
        divisor = 0,
        op = DivOp.DIV,
        expected = BigInt("FFFFFFFF", 16) // -1
      ),
      // Overflow: -2^31 / -1 = -2^31 (not +2^31 which would overflow)
      DivVector(
        dividend = BigInt("80000000", 16), // -2147483648
        divisor = BigInt("FFFFFFFF", 16), // -1
        op = DivOp.DIV,
        expected = BigInt("80000000", 16) // -2147483648
      )
    )

    simulate(new SimpleDivider(xlen)) { dut =>
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)

      for (vector <- vectors) {
        dut.io.inp.bits.dividend.poke(vector.dividend.U)
        dut.io.inp.bits.divisor.poke(vector.divisor.U)
        dut.io.inp.bits.op.poke(vector.op)
        dut.io.inp.valid.poke(true.B)

        dut.clock.step(dut.latency)

        dut.io.result.valid.expect(true.B)
        dut.io.result.bits.expect(vector.expected.U)

        dut.clock.step(1)
      }
    }
  }

  it should "compute DIVU (unsigned division) correctly" in {
    val vectors = Seq(
      DivVector(
        dividend = 20,
        divisor = 6,
        op = DivOp.DIVU,
        expected = 3
      ),
      DivVector(
        dividend = 100,
        divisor = 10,
        op = DivOp.DIVU,
        expected = 10
      ),
      DivVector(
        dividend = 0,
        divisor = 5,
        op = DivOp.DIVU,
        expected = 0
      ),
      // Large unsigned values
      DivVector(
        dividend = BigInt("FFFFFFFF", 16), // 4294967295
        divisor = 2,
        op = DivOp.DIVU,
        expected = BigInt("7FFFFFFF", 16) // 2147483647
      ),
      DivVector(
        dividend = BigInt("80000000", 16), // 2147483648 (unsigned)
        divisor = 2,
        op = DivOp.DIVU,
        expected = BigInt("40000000", 16) // 1073741824
      ),
      DivVector(
        dividend = BigInt("FFFFFFFF", 16),
        divisor = BigInt("FFFFFFFF", 16),
        op = DivOp.DIVU,
        expected = 1
      ),
      // Division by zero: result = 2^xlen - 1
      DivVector(
        dividend = 42,
        divisor = 0,
        op = DivOp.DIVU,
        expected = BigInt("FFFFFFFF", 16)
      )
    )

    simulate(new SimpleDivider(xlen)) { dut =>
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)

      for (vector <- vectors) {
        dut.io.inp.bits.dividend.poke(vector.dividend.U)
        dut.io.inp.bits.divisor.poke(vector.divisor.U)
        dut.io.inp.bits.op.poke(vector.op)
        dut.io.inp.valid.poke(true.B)

        dut.clock.step(dut.latency)

        dut.io.result.valid.expect(true.B)
        dut.io.result.bits.expect(vector.expected.U)

        dut.clock.step(1)
      }
    }
  }

  it should "compute REM (signed remainder) correctly" in {
    val vectors = Seq(
      DivVector(
        dividend = 20,
        divisor = 6,
        op = DivOp.REM,
        expected = 2
      ),
      DivVector(
        dividend = 7,
        divisor = 3,
        op = DivOp.REM,
        expected = 1
      ),
      DivVector(
        dividend = 10,
        divisor = 5,
        op = DivOp.REM,
        expected = 0
      ),
      // Negative dividend: sign of remainder follows dividend
      DivVector(
        dividend = BigInt("FFFFFFEC", 16), // -20
        divisor = 6,
        op = DivOp.REM,
        expected = BigInt("FFFFFFFE", 16) // -2
      ),
      // Negative divisor: sign of remainder follows dividend
      DivVector(
        dividend = 20,
        divisor = BigInt("FFFFFFFA", 16), // -6
        op = DivOp.REM,
        expected = 2
      ),
      // Both negative
      DivVector(
        dividend = BigInt("FFFFFFEC", 16), // -20
        divisor = BigInt("FFFFFFFA", 16), // -6
        op = DivOp.REM,
        expected = BigInt("FFFFFFFE", 16) // -2
      ),
      // Remainder by zero: result = dividend
      DivVector(
        dividend = 42,
        divisor = 0,
        op = DivOp.REM,
        expected = 42
      ),
      // Overflow: -2^31 % -1 = 0
      DivVector(
        dividend = BigInt("80000000", 16), // -2147483648
        divisor = BigInt("FFFFFFFF", 16), // -1
        op = DivOp.REM,
        expected = 0
      )
    )

    simulate(new SimpleDivider(xlen)) { dut =>
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)

      for (vector <- vectors) {
        dut.io.inp.bits.dividend.poke(vector.dividend.U)
        dut.io.inp.bits.divisor.poke(vector.divisor.U)
        dut.io.inp.bits.op.poke(vector.op)
        dut.io.inp.valid.poke(true.B)

        dut.clock.step(dut.latency)

        dut.io.result.valid.expect(true.B)
        dut.io.result.bits.expect(vector.expected.U)

        dut.clock.step(1)
      }
    }
  }

  it should "compute REMU (unsigned remainder) correctly" in {
    val vectors = Seq(
      DivVector(
        dividend = 20,
        divisor = 6,
        op = DivOp.REMU,
        expected = 2
      ),
      DivVector(
        dividend = 7,
        divisor = 3,
        op = DivOp.REMU,
        expected = 1
      ),
      DivVector(
        dividend = 10,
        divisor = 5,
        op = DivOp.REMU,
        expected = 0
      ),
      // Large unsigned values
      DivVector(
        dividend = BigInt("FFFFFFFF", 16), // 4294967295
        divisor = 10,
        op = DivOp.REMU,
        expected = 5
      ),
      DivVector(
        dividend = BigInt("80000000", 16), // 2147483648 (unsigned)
        divisor = 3,
        op = DivOp.REMU,
        expected = 2
      ),
      DivVector(
        dividend = BigInt("FFFFFFFF", 16),
        divisor = BigInt("FFFFFFFF", 16),
        op = DivOp.REMU,
        expected = 0
      ),
      // Remainder by zero: result = dividend
      DivVector(
        dividend = 42,
        divisor = 0,
        op = DivOp.REMU,
        expected = 42
      ),
      DivVector(
        dividend = BigInt("FFFFFFFF", 16),
        divisor = 0,
        op = DivOp.REMU,
        expected = BigInt("FFFFFFFF", 16)
      )
    )

    simulate(new SimpleDivider(xlen)) { dut =>
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)

      for (vector <- vectors) {
        dut.io.inp.bits.dividend.poke(vector.dividend.U)
        dut.io.inp.bits.divisor.poke(vector.divisor.U)
        dut.io.inp.bits.op.poke(vector.op)
        dut.io.inp.valid.poke(true.B)

        dut.clock.step(dut.latency)

        dut.io.result.valid.expect(true.B)
        dut.io.result.bits.expect(vector.expected.U)

        dut.clock.step(1)
      }
    }
  }

  it should "handle valid/ready handshake correctly" in {
    simulate(new SimpleDivider(xlen)) { dut =>
      // Module should always be ready
      dut.io.inp.ready.expect(true.B)

      // When input is not valid, output should not be valid
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)
      dut.io.result.valid.expect(false.B)

      // When input is valid, output should be valid
      dut.io.inp.bits.dividend.poke(100.U)
      dut.io.inp.bits.divisor.poke(20.U)
      dut.io.inp.bits.op.poke(DivOp.DIV)
      dut.io.inp.valid.poke(true.B)
      dut.clock.step(dut.latency)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(5.U)

      // Back to invalid
      dut.io.inp.valid.poke(false.B)
      dut.clock.step(1)
      dut.io.result.valid.expect(false.B)
    }
  }

  it should "match RISC-V M extension division edge cases" in {
    val vectors = Seq(
      // Division by 1 returns dividend
      DivVector(12345, 1, DivOp.DIV, 12345),
      DivVector(12345, 1, DivOp.DIVU, 12345),

      // Remainder by 1 is always 0
      DivVector(12345, 1, DivOp.REM, 0),
      DivVector(12345, 1, DivOp.REMU, 0),

      // Division of 0
      DivVector(0, 5, DivOp.DIV, 0),
      DivVector(0, 5, DivOp.DIVU, 0),
      DivVector(0, 5, DivOp.REM, 0),
      DivVector(0, 5, DivOp.REMU, 0),

      // All division by zero cases
      DivVector(100, 0, DivOp.DIV, BigInt("FFFFFFFF", 16)),
      DivVector(100, 0, DivOp.DIVU, BigInt("FFFFFFFF", 16)),
      DivVector(100, 0, DivOp.REM, 100),
      DivVector(100, 0, DivOp.REMU, 100),

      // Signed overflow case
      DivVector(
        BigInt("80000000", 16),
        BigInt("FFFFFFFF", 16),
        DivOp.DIV,
        BigInt("80000000", 16)
      ),
      DivVector(BigInt("80000000", 16), BigInt("FFFFFFFF", 16), DivOp.REM, 0),

      // Sign handling
      DivVector(
        BigInt("FFFFFFF6", 16),
        5,
        DivOp.DIV,
        BigInt("FFFFFFFE", 16)
      ), // -10 / 5 = -2
      DivVector(BigInt("FFFFFFF6", 16), 5, DivOp.REM, 0), // -10 % 5 = 0

      // Unsigned treats negative bit patterns as large positive
      DivVector(
        BigInt("FFFFFFF6", 16),
        5,
        DivOp.DIVU,
        BigInt("33333331", 16)
      ), // 4294967286 / 5 = 858993457
      DivVector(BigInt("FFFFFFF6", 16), 5, DivOp.REMU, 1) // 4294967286 % 5 = 1
    )

    simulate(new SimpleDivider(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.inp.bits.dividend.poke(vector.dividend.U)
        dut.io.inp.bits.divisor.poke(vector.divisor.U)
        dut.io.inp.bits.op.poke(vector.op)
        dut.io.inp.valid.poke(true.B)

        dut.clock.step(dut.latency)

        dut.io.result.valid.expect(true.B)
        dut.io.result.bits.expect(vector.expected.U)

        dut.clock.step(1)
      }
    }
  }
}
