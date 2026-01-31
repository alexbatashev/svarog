package svarog.decoder

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ZicsrInstructionsSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "ZicsrInstructions"

  private val xlen = 32

  private case class DecodeVector(
      pc: Int,
      instruction: BigInt,
      check: ZicsrInstructions => Unit
  )

  it should "decode CSR register-based instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("34011173", 16), // csrrw x2, mscratch, x2 (mscratch=0x340)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.rs1.expect(2.U)
          dut.io.decoded.csrAddr.expect(0x340.U)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("30512173", 16), // csrrs x2, mtvec, x2 (mtvec=0x305)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.rs1.expect(2.U)
          dut.io.decoded.csrAddr.expect(0x305.U)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("30413173", 16), // csrrc x2, mie, x2 (mie=0x304)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRC)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.rs1.expect(2.U)
          dut.io.decoded.csrAddr.expect(0x304.U)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        dut.io.decoded.pc.expect(vector.pc.U)
        vector.check(dut)
      }
    }
  }

  it should "decode CSR immediate-based instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("34015173", 16), // csrrwi x2, mscratch, 2 (zimm=2)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.csrAddr.expect(0x340.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(2.U) // zimm from rs1 field
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("305FE173", 16), // csrrsi x2, mtvec, 31 (zimm=31)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.csrAddr.expect(0x305.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(31.U) // zimm from rs1 field
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("3040F173", 16), // csrrci x2, mie, 1 (zimm=1)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRC)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.csrAddr.expect(0x304.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(1.U) // zimm from rs1 field
          dut.io.decoded.regWrite.expect(true.B)
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        dut.io.decoded.pc.expect(vector.pc.U)
        vector.check(dut)
      }
    }
  }

  it should "decode various CSR addresses" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("F1402573", 16), // csrrs x10, mhartid, x0 (mhartid=0xF14)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.csrAddr.expect(0xF14.U)
          dut.io.decoded.rd.expect(10.U)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("C00025F3", 16), // csrrs x11, cycle, x0 (cycle=0xC00)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.csrAddr.expect(0xC00.U)
          dut.io.decoded.rd.expect(11.U)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("C8102673", 16), // csrrs x12, cycleh, x0 (cycleh=0xC81)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.csrAddr.expect(0xC81.U)
          dut.io.decoded.rd.expect(12.U)
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "not write to x0 register" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("34011073", 16), // csrrw x0, mscratch, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.rd.expect(0.U)
          dut.io.decoded.regWrite.expect(false.B) // Should be false for x0
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("30512073", 16), // csrrs x0, mtvec, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.rd.expect(0.U)
          dut.io.decoded.regWrite.expect(false.B) // Should be false for x0
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("34011173", 16), // csrrw x2, mscratch, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.rd.expect(2.U)
          dut.io.decoded.regWrite.expect(true.B) // Should be true for non-x0
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "handle all three CSR operation types" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("34029073", 16), // csrrw x0, mscratch, x5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.rs1.expect(5.U)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("3052A0F3", 16), // csrrs x1, mtvec, x5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.rs1.expect(5.U)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("3042B173", 16), // csrrc x2, mie, x5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRC)
          dut.io.decoded.rs1.expect(5.U)
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "mark non-CSR instructions as INVALID" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00208133", 16), // add x2, x1, x2 (wrong opcode)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("00000073", 16), // ecall (SYSTEM opcode but wrong funct3)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("00000000", 16), // Invalid (all zeros)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "correctly extract zimm for immediate variants" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("34005173", 16), // csrrwi x2, mscratch, 0
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(0.U)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("3050E173", 16), // csrrsi x2, mtvec, 1
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(1.U)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("304FF173", 16), // csrrci x2, mie, 31
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRC)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(31.U)
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "zero-extend zimm immediate (not sign-extend)" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("34005173", 16), // csrrwi x2, mscratch, 0 (zimm=0b00000)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(BigInt("00000000", 16).U) // Zero-extended
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("3050E173", 16), // csrrsi x2, mtvec, 1 (zimm=0b00001)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRS)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(BigInt("00000001", 16).U) // Zero-extended
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("304FF173", 16), // csrrci x2, mie, 31 (zimm=0b11111)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRC)
          dut.io.decoded.hasImm.expect(true.B)
          // zimm=31 (0b11111) should be zero-extended to 0x0000001F, NOT sign-extended to 0xFFFFFFFF
          dut.io.decoded.imm.expect(BigInt("0000001F", 16).U)
        }
      ),
      DecodeVector(
        pc = 12,
        instruction = BigInt("34085173", 16), // csrrwi x2, mscratch, 16 (zimm=0b10000)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.hasImm.expect(true.B)
          // zimm=16 (0b10000) should be zero-extended to 0x00000010, NOT sign-extended to 0xFFFFFFF0
          dut.io.decoded.imm.expect(BigInt("00000010", 16).U)
        }
      ),
      DecodeVector(
        pc = 16,
        instruction = BigInt("3048D173", 16), // csrrwi x2, mie, 17 (zimm=0b10001)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.CSRRW)
          dut.io.decoded.hasImm.expect(true.B)
          // zimm=17 (0b10001) should be zero-extended to 0x00000011
          dut.io.decoded.imm.expect(BigInt("00000011", 16).U)
        }
      )
    )

    simulate(new ZicsrInstructions(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }
}
