package svarog.decoder

import chisel3._
import chisel3.util._
import svarog.bits.ALUOp
import svarog.memory.MemWidth

case class BaseInstructions(xlen: Int) extends Module {
  // require(xlen >= 32, "BaseInstructions requires XLEN >= 32")

  val io = IO(new Bundle {
    val immGen = Flipped(new ImmGenIO(xlen))
    val decoded = new MicroOp(xlen)
    val pc = Input(UInt(xlen.W))
    val instruction = Input(UInt(32.W))
  })

  // def decode(instruction: UInt, immGen: ImmGen): MicroOp = {
  val opcode = io.instruction(6, 0)
  val rd = io.instruction(11, 7)
  val rs1 = io.instruction(19, 15)
  val rs2 = io.instruction(24, 20)
  val funct3 = io.instruction(14, 12)
  val funct7 = io.instruction(31, 25)
  val inst = io.instruction

  io.immGen.instruction := io.instruction
  io.immGen.format := ImmFormat.I // Default format
  // Always assign immediate from immGen output (used when hasImm is true)
  io.decoded.imm := io.immGen.immediate

  io.decoded.rd := rd
  io.decoded.rs1 := rs1
  io.decoded.rs2 := rs2
  io.decoded.hasImm := false.B
  io.decoded.memWidth := MemWidth.WORD
  io.decoded.memUnsigned := false.B
  io.decoded.branchFunc := BranchOp.INVALID
  io.decoded.regWrite := false.B
  io.decoded.pc := io.pc
  io.decoded.isEcall := false.B
  io.decoded.aluOp := ALUOp.ADD
  io.decoded.opType := OpType.INVALID

  switch(opcode) {
    is(Opcodes.ALU_REG) {
      io.decoded.opType := OpType.ALU
      io.decoded.hasImm := false.B
      io.decoded.regWrite := rd =/= 0.U
      io.immGen.format := ImmFormat.I

      switch(funct3) {
        is(ALUFunc3.ADD_SUB) {
          // Use inst(30) which is funct7(5) to distinguish ADD/SUB
          io.decoded.aluOp := Mux(inst(30), ALUOp.SUB, ALUOp.ADD)
        }
        is(ALUFunc3.SLL) {
          io.decoded.aluOp := ALUOp.SLL
        }
        is(ALUFunc3.SLT) {
          io.decoded.aluOp := ALUOp.SLT
        }
        is(ALUFunc3.SLTU) {
          io.decoded.aluOp := ALUOp.SLTU
        }
        is(ALUFunc3.XOR) {
          io.decoded.aluOp := ALUOp.XOR
        }
        is(ALUFunc3.SRL_SRA) {
          // Use funct7(5) to distinguish SRL/SRA
          io.decoded.aluOp := Mux(funct7(5), ALUOp.SRA, ALUOp.SRL)
        }
        is(ALUFunc3.OR) {
          io.decoded.aluOp := ALUOp.OR
        }
        is(ALUFunc3.AND) {
          io.decoded.aluOp := ALUOp.AND
        }
      }
    }
    is(Opcodes.ALU_IMM) {
      io.decoded.opType := OpType.ALU
      io.decoded.hasImm := true.B
      io.decoded.regWrite := rd =/= 0.U
      io.immGen.format := ImmFormat.I

      switch(funct3) {
        is(ALUFunc3.ADD_SUB) { io.decoded.aluOp := ALUOp.ADD }
        is(ALUFunc3.SLT) { io.decoded.aluOp := ALUOp.SLT }
        is(ALUFunc3.SLTU) { io.decoded.aluOp := ALUOp.SLTU }
        is(ALUFunc3.XOR) { io.decoded.aluOp := ALUOp.XOR }
        is(ALUFunc3.OR) { io.decoded.aluOp := ALUOp.OR }
        is(ALUFunc3.AND) { io.decoded.aluOp := ALUOp.AND }
        is(ALUFunc3.SLL) { io.decoded.aluOp := ALUOp.SLL }
        is(ALUFunc3.SRL_SRA) {
          // SRLI/SRAI: use funct7(5) to distinguish
          io.decoded.aluOp := Mux(funct7(5), ALUOp.SRA, ALUOp.SRL)
        }
      }
    }
    is(Opcodes.LOAD) {
      io.decoded.opType := OpType.LOAD
      io.decoded.hasImm := true.B
      io.decoded.regWrite := rd =/= 0.U
      io.immGen.format := ImmFormat.I

      switch(funct3) {
        is("b000".U) { // LB
          io.decoded.memWidth := MemWidth.BYTE
          io.decoded.memUnsigned := false.B
        }
        is("b001".U) { // LH
          io.decoded.memWidth := MemWidth.HALF
          io.decoded.memUnsigned := false.B
        }
        is("b010".U) { // LW
          io.decoded.memWidth := MemWidth.WORD
          io.decoded.memUnsigned := false.B
        }
        is("b100".U) { // LBU
          io.decoded.memWidth := MemWidth.BYTE
          io.decoded.memUnsigned := true.B
        }
        is("b101".U) { // LHU
          io.decoded.memWidth := MemWidth.HALF
          io.decoded.memUnsigned := true.B
        }
      }
    }

    is(Opcodes.STORE) {
      io.decoded.opType := OpType.STORE
      io.decoded.hasImm := true.B
      io.decoded.regWrite := false.B
      io.immGen.format := ImmFormat.S

      switch(funct3) {
        is("b000".U) { io.decoded.memWidth := MemWidth.BYTE } // SB
        is("b001".U) { io.decoded.memWidth := MemWidth.HALF } // SH
        is("b010".U) { io.decoded.memWidth := MemWidth.WORD } // SW
      }
    }

    is(Opcodes.LUI) {
      io.decoded.opType := OpType.LUI
      io.decoded.hasImm := true.B
      io.decoded.regWrite := rd =/= 0.U
      io.immGen.format := ImmFormat.U
    }

    is(Opcodes.AUIPC) {
      io.decoded.opType := OpType.AUIPC
      io.decoded.hasImm := true.B
      io.decoded.regWrite := rd =/= 0.U
      io.immGen.format := ImmFormat.U
    }

    is(Opcodes.BRANCH) {
      io.decoded.opType := OpType.BRANCH
      io.decoded.hasImm := true.B
      io.decoded.regWrite := false.B
      io.immGen.format := ImmFormat.B
      io.decoded.branchFunc := BranchOp.fromFunct3(funct3)
    }

    is(Opcodes.JAL) {
      io.decoded.opType := OpType.JAL
      io.decoded.hasImm := true.B
      io.decoded.regWrite := rd =/= 0.U
      io.immGen.format := ImmFormat.J
    }

    is(Opcodes.JALR) {
      io.decoded.opType := OpType.JALR
      io.decoded.hasImm := true.B
      io.decoded.regWrite := rd =/= 0.U
      io.immGen.format := ImmFormat.I
    }

    is(Opcodes.SYSTEM) {
      when(inst === "h00000073".U) { // ECALL
        io.decoded.opType := OpType.SYSTEM
        io.decoded.regWrite := false.B
        io.decoded.isEcall := true.B
      }.elsewhen(funct3 =/= 0.U) { // CSR instructions (Zicsr extension)
        // Note: CSR is not part of base RV32I but commonly supported
        // Treat CSR reads as ALU operations
        io.decoded.opType := OpType.ALU
        io.decoded.aluOp := ALUOp.ADD
        io.decoded.hasImm := funct3(2) // CSRRxI instructions use immediate
        io.decoded.regWrite := rd =/= 0.U
        io.immGen.format := ImmFormat.I
      }.otherwise {
        io.decoded.opType := OpType.NOP
      }
    }
  }

}
