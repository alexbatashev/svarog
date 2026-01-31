package svarog.decoder

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import svarog.bits.ALUOp
import svarog.memory.MemWidth

// Test harness that wraps BaseInstructions with ImmGen
private class BaseInstructionsHarness(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val pc = Input(UInt(xlen.W))
    val decoded = new MicroOp(xlen)
  })

  val immGen = Module(new ImmGen(xlen))
  val decoder = Module(new BaseInstructions(xlen))

  decoder.io.immGen <> immGen.io
  decoder.io.instruction := io.instruction
  decoder.io.pc := io.pc
  io.decoded := decoder.io.decoded
}

class BaseInstructionsSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "BaseInstructions"

  private val xlen = 32

  private case class DecodeVector(
      pc: Int,
      instruction: BigInt,
      check: BaseInstructionsHarness => Unit
  )

  it should "decode R-type ALU instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("002081B3", 16), // add x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.ADD)
          dut.io.decoded.rd.expect(3.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.rs2.expect(2.U)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("402081B3", 16), // sub x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SUB)
          dut.io.decoded.rd.expect(3.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.rs2.expect(2.U)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("002091B3", 16), // sll x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SLL)
          dut.io.decoded.rd.expect(3.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 12,
        instruction = BigInt("0020A1B3", 16), // slt x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SLT)
        }
      ),
      DecodeVector(
        pc = 16,
        instruction = BigInt("0020B1B3", 16), // sltu x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SLTU)
        }
      ),
      DecodeVector(
        pc = 20,
        instruction = BigInt("0020C1B3", 16), // xor x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.XOR)
        }
      ),
      DecodeVector(
        pc = 24,
        instruction = BigInt("0020D1B3", 16), // srl x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SRL)
        }
      ),
      DecodeVector(
        pc = 28,
        instruction = BigInt("4020D1B3", 16), // sra x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SRA)
        }
      ),
      DecodeVector(
        pc = 32,
        instruction = BigInt("0020E1B3", 16), // or x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.OR)
        }
      ),
      DecodeVector(
        pc = 36,
        instruction = BigInt("0020F1B3", 16), // and x3, x1, x2
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.AND)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        dut.io.decoded.pc.expect(vector.pc.U)
        vector.check(dut)
      }
    }
  }

  it should "decode I-type ALU instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00500093", 16), // addi x1, x0, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.ADD)
          dut.io.decoded.rd.expect(1.U)
          dut.io.decoded.rs1.expect(0.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(5.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("FFF08093", 16), // addi x1, x1, -1
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.ADD)
          dut.io.decoded.rd.expect(1.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm
            .expect(BigInt("FFFFFFFF", 16).U) // sign-extended -1
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("0050A093", 16), // slti x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SLT)
          dut.io.decoded.hasImm.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 12,
        instruction = BigInt("0050B093", 16), // sltiu x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SLTU)
        }
      ),
      DecodeVector(
        pc = 16,
        instruction = BigInt("0050C093", 16), // xori x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.XOR)
        }
      ),
      DecodeVector(
        pc = 20,
        instruction = BigInt("0050E093", 16), // ori x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.OR)
        }
      ),
      DecodeVector(
        pc = 24,
        instruction = BigInt("0050F093", 16), // andi x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.AND)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode I-type shift instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00509093", 16), // slli x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SLL)
          dut.io.decoded.rd.expect(1.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(5.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("0050D093", 16), // srli x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SRL)
          dut.io.decoded.hasImm.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("4050D093", 16), // srai x1, x1, 5
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ALU)
          dut.io.decoded.aluOp.expect(ALUOp.SRA)
          dut.io.decoded.hasImm.expect(true.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode load instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("0080A203", 16), // lw x4, 8(x1)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.LOAD)
          dut.io.decoded.rd.expect(4.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(8.U)
          dut.io.decoded.memWidth.expect(MemWidth.WORD)
          dut.io.decoded.memUnsigned.expect(false.B)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("00808203", 16), // lb x4, 8(x1)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.LOAD)
          dut.io.decoded.memWidth.expect(MemWidth.BYTE)
          dut.io.decoded.memUnsigned.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("00809203", 16), // lh x4, 8(x1)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.LOAD)
          dut.io.decoded.memWidth.expect(MemWidth.HALF)
          dut.io.decoded.memUnsigned.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 12,
        instruction = BigInt("0080C203", 16), // lbu x4, 8(x1)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.LOAD)
          dut.io.decoded.memWidth.expect(MemWidth.BYTE)
          dut.io.decoded.memUnsigned.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 16,
        instruction = BigInt("0080D203", 16), // lhu x4, 8(x1)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.LOAD)
          dut.io.decoded.memWidth.expect(MemWidth.HALF)
          dut.io.decoded.memUnsigned.expect(true.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode store instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00812423", 16), // sw x8, 8(x2)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.STORE)
          dut.io.decoded.rs1.expect(2.U)
          dut.io.decoded.rs2.expect(8.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(8.U)
          dut.io.decoded.memWidth.expect(MemWidth.WORD)
          dut.io.decoded.regWrite.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("00810423", 16), // sb x8, 8(x2)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.STORE)
          dut.io.decoded.memWidth.expect(MemWidth.BYTE)
          dut.io.decoded.regWrite.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("00811423", 16), // sh x8, 8(x2)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.STORE)
          dut.io.decoded.memWidth.expect(MemWidth.HALF)
          dut.io.decoded.regWrite.expect(false.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode branch instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00208463", 16), // beq x1, x2, 8
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.BRANCH)
          dut.io.decoded.branchFunc.expect(BranchOp.BEQ)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.rs2.expect(2.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(8.U)
          dut.io.decoded.regWrite.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("00209463", 16), // bne x1, x2, 8
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.BRANCH)
          dut.io.decoded.branchFunc.expect(BranchOp.BNE)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("0020C463", 16), // blt x1, x2, 8
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.BRANCH)
          dut.io.decoded.branchFunc.expect(BranchOp.BLT)
        }
      ),
      DecodeVector(
        pc = 12,
        instruction = BigInt("0020D463", 16), // bge x1, x2, 8
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.BRANCH)
          dut.io.decoded.branchFunc.expect(BranchOp.BGE)
        }
      ),
      DecodeVector(
        pc = 16,
        instruction = BigInt("0020E463", 16), // bltu x1, x2, 8
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.BRANCH)
          dut.io.decoded.branchFunc.expect(BranchOp.BLTU)
        }
      ),
      DecodeVector(
        pc = 20,
        instruction = BigInt("0020F463", 16), // bgeu x1, x2, 8
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.BRANCH)
          dut.io.decoded.branchFunc.expect(BranchOp.BGEU)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode U-type instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("123450B7", 16), // lui x1, 0x12345
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.LUI)
          dut.io.decoded.rd.expect(1.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(BigInt("12345000", 16).U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("12345097", 16), // auipc x1, 0x12345
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.AUIPC)
          dut.io.decoded.rd.expect(1.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(BigInt("12345000", 16).U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode JAL instruction" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("008000EF", 16), // jal x1, 8
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.JAL)
          dut.io.decoded.rd.expect(1.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(8.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode JALR instruction" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("008080E7", 16), // jalr x1, 8(x1)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.JALR)
          dut.io.decoded.rd.expect(1.U)
          dut.io.decoded.rs1.expect(1.U)
          dut.io.decoded.hasImm.expect(true.B)
          dut.io.decoded.imm.expect(8.U)
          dut.io.decoded.regWrite.expect(true.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode FENCE and FENCE.I instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("0000000F", 16), // fence
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.FENCE)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("0000100F", 16), // fence.i
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.FENCE_I)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction =
          BigInt("8330000F", 16), // fence.tso (specific encoding of fence)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.FENCE)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(false.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "decode ECALL and EBREAK instructions" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00000073", 16), // ecall
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.ECALL)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(false.B)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("00100073", 16), // ebreak
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.EBREAK)
          dut.io.decoded.hasImm.expect(false.B)
          dut.io.decoded.regWrite.expect(false.B)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }

  it should "mark invalid instructions as INVALID" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00000000", 16), // Invalid (all zeros)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("FFFFFFFF", 16), // Invalid (all ones)
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("12345678", 16), // Invalid random bits
        check = { dut =>
          dut.io.decoded.opType.expect(OpType.INVALID)
        }
      )
    )

    simulate(new BaseInstructionsHarness(xlen)) { dut =>
      for (vector <- vectors) {
        dut.io.instruction.poke(vector.instruction.U)
        dut.io.pc.poke(vector.pc.U)
        dut.clock.step(1)
        vector.check(dut)
      }
    }
  }
}
