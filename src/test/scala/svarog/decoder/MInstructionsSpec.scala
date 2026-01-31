package svarog.decoder

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import svarog.bits.MulOp
import svarog.bits.DivOp

class MInstructionsSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "MInstructions"

  private val xlen = 32

  private case class DecodeVector(
      pc: Int,
      instruction: BigInt,
      check: MInstructions => Unit
  )

  it should "decode multiply instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("02208133", 16), // mul x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.MUL)
          dut.io.decoded.mulOp.expect(MulOp.MUL)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.rs2.expect(2.U)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("02209133", 16), // mulh x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.MUL)
          dut.io.decoded.mulOp.expect(MulOp.MULH)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.rs2.expect(2.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("0220A133", 16), // mulhsu x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.MUL)
          dut.io.decoded.mulOp.expect(MulOp.MULHSU)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 12,
        instruction = BigInt("0220B133", 16), // mulhu x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.MUL)
          dut.io.decoded.mulOp.expect(MulOp.MULHU)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      )
    )

    simulate(new MInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        dut.io.decoded.pc.expect(vector.pc.U)
        vector.check(dut)
      }
    }
  }

  it should "decode divide instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("0220C133", 16), // div x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.DIV)
          dut.io.decoded.divOp.expect(DivOp.DIV)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.rs2.expect(2.U)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("0220D133", 16), // divu x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.DIV)
          dut.io.decoded.divOp.expect(DivOp.DIVU)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.rs2.expect(2.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("0220E133", 16), // rem x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.DIV)
          dut.io.decoded.divOp.expect(DivOp.REM)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 12,
        instruction = BigInt("0220F133", 16), // remu x2, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.DIV)
          dut.io.decoded.divOp.expect(DivOp.REMU)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      )
    )

    simulate(new MInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        dut.io.decoded.pc.expect(vector.pc.U)
        vector.check(dut)
      }
    }
  }

  it should "mark non-M-extension instructions as INVALID" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00208133", 16), // add x2, x1, x2 (wrong funct7)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("00000000", 16), // Invalid (all zeros)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("FFFFFFFF", 16), // Invalid (all ones)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      )
    )

    simulate(new MInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode all multiply variants with different register operands" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("03E48AB3", 16), // mul x21, x9, x30
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.MUL)
          dut.io.decoded.mulOp.expect(MulOp.MUL)
          dut.io.decoded.rd.expect(21.U)
          dut.io.decoded.rs1.expect(9.U)
          dut.io.decoded.rs2.expect(30.U)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("02FF9FB3", 16), // mulh x31, x31, x15
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.MUL)
          dut.io.decoded.mulOp.expect(MulOp.MULH)
          dut.io.decoded.rd.expect(31.U)
          dut.io.decoded.rs1.expect(31.U)
          dut.io.decoded.rs2.expect(15.U)
        }
      )
    )

    simulate(new MInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode all divide variants with different register operands" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("02A5C5B3", 16), // div x11, x11, x10
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.DIV)
          dut.io.decoded.divOp.expect(DivOp.DIV)
          dut.io.decoded.rd.expect(11.U)
          dut.io.decoded.rs1.expect(11.U)
          dut.io.decoded.rs2.expect(10.U)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("021ADFB3", 16), // divu x31, x21, x1
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.DIV)
          dut.io.decoded.divOp.expect(DivOp.DIVU)
          dut.io.decoded.rd.expect(31.U)
          dut.io.decoded.rs1.expect(21.U)
          dut.io.decoded.rs2.expect(1.U)
        }
      )
    )

    simulate(new MInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }
}
