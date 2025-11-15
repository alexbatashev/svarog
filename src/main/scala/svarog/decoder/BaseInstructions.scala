package svarog.decoder

import chisel3._
import chisel3.util._
import svarog.bits.ALUOp
import svarog.memory.MemWidth

case class BaseInstructions(xlen: Int) {
  require(xlen >= 32, "BaseInstructions requires XLEN >= 32")

  def decode(instruction: UInt, immGen: ImmGen): MicroOp = {
    val opcode = instruction(6, 0)
    val rd = instruction(11, 7)
    val rs1 = instruction(19, 15)
    val rs2 = instruction(24, 20)
    val funct3 = instruction(14, 12)
    val funct7 = instruction(31, 25)
    val inst = instruction

    immGen.io.instruction := instruction
    immGen.io.format := ImmFormat.I // Default format

    val decoded = Wire(new MicroOp(xlen))
    decoded.rd := rd
    decoded.rs1 := rs1
    decoded.rs2 := rs2
    decoded.hasImm := false.B
    decoded.imm := 0.U
    decoded.memWidth := MemWidth.WORD
    decoded.memUnsigned := false.B
    decoded.branchFunc := 0.U
    decoded.regWrite := false.B
    decoded.valid := true.B  // Mark as valid by default
    decoded.pc := 0.U
    decoded.isEcall := false.B
    decoded.aluOp := ALUOp.ADD
    decoded.opType := OpType.NOP

    switch(opcode) {
      is(Opcodes.ALU_REG) {
        decoded.opType := OpType.ALU
        decoded.hasImm := false.B
        decoded.regWrite := rd =/= 0.U
        immGen.io.format := ImmFormat.I

        switch(funct3) {
          is(ALUFunc3.ADD_SUB) {
            // Use inst(30) which is funct7(5) to distinguish ADD/SUB
            decoded.aluOp := Mux(inst(30), ALUOp.SUB, ALUOp.ADD)
          }
          is(ALUFunc3.SLL) { decoded.aluOp := ALUOp.SLL }
          is(ALUFunc3.SLT) { decoded.aluOp := ALUOp.SLT }
          is(ALUFunc3.SLTU) { decoded.aluOp := ALUOp.SLTU }
          is(ALUFunc3.XOR) { decoded.aluOp := ALUOp.XOR }
          is(ALUFunc3.SRL_SRA) {
            // Use funct7(5) to distinguish SRL/SRA
            decoded.aluOp := Mux(funct7(5), ALUOp.SRA, ALUOp.SRL)
          }
          is(ALUFunc3.OR) { decoded.aluOp := ALUOp.OR }
          is(ALUFunc3.AND) { decoded.aluOp := ALUOp.AND }
        }
      }
      is(Opcodes.ALU_IMM) {
        decoded.opType := OpType.ALU
        decoded.hasImm := true.B
        decoded.regWrite := rd =/= 0.U
        immGen.io.format := ImmFormat.I

        switch(funct3) {
          is(ALUFunc3.ADD_SUB) { decoded.aluOp := ALUOp.ADD }
          is(ALUFunc3.SLT) { decoded.aluOp := ALUOp.SLT }
          is(ALUFunc3.SLTU) { decoded.aluOp := ALUOp.SLTU }
          is(ALUFunc3.XOR) { decoded.aluOp := ALUOp.XOR }
          is(ALUFunc3.OR) { decoded.aluOp := ALUOp.OR }
          is(ALUFunc3.AND) { decoded.aluOp := ALUOp.AND }
          is(ALUFunc3.SLL) { decoded.aluOp := ALUOp.SLL }
          is(ALUFunc3.SRL_SRA) {
            // SRLI/SRAI: use funct7(5) to distinguish
            decoded.aluOp := Mux(funct7(5), ALUOp.SRA, ALUOp.SRL)
          }
        }
      }
      is(Opcodes.LOAD) {
        decoded.opType := OpType.LOAD
        decoded.hasImm := true.B
        decoded.regWrite := rd =/= 0.U
        immGen.io.format := ImmFormat.I

        switch(funct3) {
          is("b000".U) { // LB
            decoded.memWidth := MemWidth.BYTE
            decoded.memUnsigned := false.B
          }
          is("b001".U) { // LH
            decoded.memWidth := MemWidth.HALF
            decoded.memUnsigned := false.B
          }
          is("b010".U) { // LW
            decoded.memWidth := MemWidth.WORD
            decoded.memUnsigned := false.B
          }
          is("b100".U) { // LBU
            decoded.memWidth := MemWidth.BYTE
            decoded.memUnsigned := true.B
          }
          is("b101".U) { // LHU
            decoded.memWidth := MemWidth.HALF
            decoded.memUnsigned := true.B
          }
        }
      }

      is(Opcodes.STORE) {
        decoded.opType := OpType.STORE
        decoded.hasImm := true.B
        decoded.regWrite := false.B
        immGen.io.format := ImmFormat.S

        switch(funct3) {
          is("b000".U) { decoded.memWidth := MemWidth.BYTE } // SB
          is("b001".U) { decoded.memWidth := MemWidth.HALF } // SH
          is("b010".U) { decoded.memWidth := MemWidth.WORD } // SW
        }
      }

      is(Opcodes.LUI) {
        decoded.opType := OpType.LUI
        decoded.hasImm := true.B
        decoded.regWrite := rd =/= 0.U
        immGen.io.format := ImmFormat.U
      }

      is(Opcodes.AUIPC) {
        decoded.opType := OpType.AUIPC
        decoded.hasImm := true.B
        decoded.regWrite := rd =/= 0.U
        immGen.io.format := ImmFormat.U
      }

      is(Opcodes.BRANCH) {
        decoded.opType := OpType.BRANCH
        decoded.hasImm := true.B
        decoded.regWrite := false.B
        immGen.io.format := ImmFormat.B
        decoded.branchFunc := funct3
      }

      is(Opcodes.JAL) {
        decoded.opType := OpType.JAL
        decoded.hasImm := true.B
        decoded.regWrite := rd =/= 0.U
        immGen.io.format := ImmFormat.J
      }

      is(Opcodes.JALR) {
        decoded.opType := OpType.JALR
        decoded.hasImm := true.B
        decoded.regWrite := rd =/= 0.U
        immGen.io.format := ImmFormat.I
      }

      is(Opcodes.SYSTEM) {
        when(inst === "h00000073".U) { // ECALL
          decoded.opType := OpType.SYSTEM
          decoded.regWrite := false.B
          decoded.isEcall := true.B
        }.elsewhen(funct3 =/= 0.U) { // CSR instructions (Zicsr extension)
          // Note: CSR is not part of base RV32I but commonly supported
          // Treat CSR reads as ALU operations
          decoded.opType := OpType.ALU
          decoded.aluOp := ALUOp.ADD
          decoded.hasImm := funct3(2) // CSRRxI instructions use immediate
          decoded.regWrite := rd =/= 0.U
          immGen.io.format := ImmFormat.I
        }.otherwise {
          decoded.opType := OpType.NOP
        }
      }
    }

    // Always assign immediate from immGen output (used when hasImm is true)
    decoded.imm := immGen.io.immediate

    decoded
  }
}

object BaseInstructions {
  def apply(xlen: Int): BaseInstructions = new BaseInstructions(xlen)
}
