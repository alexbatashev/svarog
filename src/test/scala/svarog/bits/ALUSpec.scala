package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class ALUSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "ALU"

  val xlen = 32

  // Helper to get the 32-bit two's complement representation of a negative number
  def to32Bit(value: Int): BigInt = {
    if (value >= 0) BigInt(value)
    else (BigInt(1) << xlen) + value
  }

  it should "add numbers correctly" in {
    simulate(new ALU(32)) { dut =>
      dut.io.op.poke(ALUOp.ADD)
      dut.io.input1.poke(5)
      dut.io.input2.poke(10)

      dut.clock.step(1)

      dut.io.output.expect(15)
    }
  }

  it should "subtract numbers correctly" in {
    simulate(new ALU(32)) { dut =>
      dut.io.op.poke(ALUOp.SUB)
      dut.io.input1.poke(10)
      dut.io.input2.poke(5)

      dut.clock.step(1)

      dut.io.output.expect(5)
    }
  }

  it should "perform bitwise AND" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.AND)
      dut.io.input1.poke("b1100".U) // 12
      dut.io.input2.poke("b1010".U) // 10
      dut.io.output.expect("b1000".U) // 8
    }
  }

  it should "perform bitwise OR" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.OR)
      dut.io.input1.poke("b1100".U)
      dut.io.input2.poke("b1010".U)
      dut.io.output.expect("b1110".U) // 14
    }
  }

  it should "perform bitwise XOR" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.XOR)
      dut.io.input1.poke("b1100".U)
      dut.io.input2.poke("b1010".U)
      dut.io.output.expect("b0110".U) // 6
    }
  }

  it should "shift left logically" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.SLL)
      dut.io.input1.poke(5.U) // 0b101
      dut.io.input2.poke(3.U)
      dut.io.output.expect(40.U) // 0b101000
    }
  }

  it should "shift right logically" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.SRL)
      dut.io.input1.poke(40.U)
      dut.io.input2.poke(3.U)
      dut.io.output.expect(5.U)
    }
  }

  it should "shift right arithmetically" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.SRA)
      dut.io.input1.poke(to32Bit(-20)) // 0xFFFFFFEC
      dut.io.input2.poke(2.U)
      dut.io.output.expect(to32Bit(-5)) // 0xFFFFFFFB
    }
  }

  it should "set less than (signed)" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.SLT)

      // -5 < 10 => true
      dut.io.input1.poke(to32Bit(-5))
      dut.io.input2.poke(10.U)
      dut.io.output.expect(1.U)

      // 10 < -5 => false
      dut.io.input1.poke(10.U)
      dut.io.input2.poke(to32Bit(-5))
      dut.io.output.expect(0.U)
    }
  }

  it should "set less than (unsigned)" in {
    simulate(new ALU(xlen)) { dut =>
      dut.io.op.poke(ALUOp.SLTU)

      // 10 < 20 => true
      dut.io.input1.poke(10.U)
      dut.io.input2.poke(20.U)
      dut.io.output.expect(1.U)

      // -5 (large unsigned) < 10 => false
      dut.io.input1.poke(to32Bit(-5))
      dut.io.input2.poke(10.U)
      dut.io.output.expect(0.U)
    }
  }
}
