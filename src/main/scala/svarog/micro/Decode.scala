package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.ImmGen
import svarog.bits.ImmFormat
import svarog.bits.Opcodes
import svarog.bits.ALUFunc3

object OpType extends ChiselEnum {
  val NOP, ALU, LOAD, STORE, BRANCH, JAL, JALR, LUI, AUIPC = Value
}

object ALUOp extends ChiselEnum {
  val ADD, SUB, OR, XOR, AND, SLT, SLTU, SLL, SRL, SRA = Value
}

object MemWidth extends ChiselEnum {
  val BYTE, HALF, WORD, DWORD = Value
}

class DecoderUOp(xlen: Int) extends Bundle {
  val opType = Output(OpType())
  val aluOp = Output(ALUOp())
  val rd = Output(UInt(5.W))
  val rs1 = Output(UInt(5.W))
  val rs2 = Output(UInt(5.W))
  val hasImm = Output(Bool())
  val imm = Output(UInt(xlen.W))
  val memWidth = Output(MemWidth())
  val memUnsigned = Output(Bool())
  val regWrite = Output(Bool())
  val valid = Output(Bool())
  val pc = Output(UInt(xlen.W))
}

class DecoderIO(xlen: Int) extends Bundle {
  val instruction = Input(UInt(32.W))
  val cur_pc = Input(UInt(xlen.W))

  val valid = Input(Bool())
  val stall = Input(Bool())

  val uop = new DecoderUOp(xlen)
}

class Decode(xlen: Int) extends Module {
  val io = IO(new DecoderIO(xlen))

  val immGen = Module(new ImmGen(xlen))
  immGen.io.instruction := io.instruction

  val inst = io.instruction
  val opcode = inst(6, 0)
  val rd = inst(11, 7)
  val funct3 = inst(14, 12)
  val rs1 = inst(19, 15)
  val rs2 = inst(24, 20)
  val funct7 = inst(31, 25)

  io.uop.opType := OpType.NOP
  io.uop.aluOp := ALUOp.ADD
  io.uop.rd := rd
  io.uop.rs1 := rs1
  io.uop.rs2 := rs2
  io.uop.hasImm := false.B
  io.uop.imm := 0.U
  io.uop.memWidth := MemWidth.BYTE
  io.uop.memUnsigned := false.B
  io.uop.regWrite := false.B
  io.uop.valid := io.valid && !io.stall
  io.uop.pc := io.cur_pc

  immGen.io.format := ImmFormat.I

  switch(opcode) {
    // R-type: ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND
    is(Opcodes.ALU_REG) {
      io.uop.opType := OpType.ALU
      io.uop.hasImm := false.B
      io.uop.regWrite := rd =/= 0.U
      immGen.io.format := ImmFormat.I // Not used but set default

      switch(funct3) {
        is(ALUFunc3.ADD_SUB) {
          io.uop.aluOp := Mux(
            funct7(5),
            ALUOp.SUB,
            ALUOp.ADD
          )
        }
        is(ALUFunc3.SLL) { io.uop.aluOp := ALUOp.SLL }
        is(ALUFunc3.SLT) { io.uop.aluOp := ALUOp.SLT }
        is(ALUFunc3.SLTU) { io.uop.aluOp := ALUOp.SLTU }
        is(ALUFunc3.XOR) { io.uop.aluOp := ALUOp.XOR }
        is(ALUFunc3.SRL_SRA) {
          io.uop.aluOp := Mux(
            funct7(5),
            ALUOp.SRA,
            ALUOp.SRL
          )
        }
        is(ALUFunc3.OR) { io.uop.aluOp := ALUOp.OR }
        is(ALUFunc3.AND) { io.uop.aluOp := ALUOp.AND }
      }
    }

    // I-type: ADDI, SLTI, SLTIU, XORI, ORI, ANDI, SLLI, SRLI, SRAI
    is(Opcodes.ALU_IMM) {
      io.uop.opType := OpType.ALU
      io.uop.hasImm := true.B
      io.uop.regWrite := rd =/= 0.U
      immGen.io.format := ImmFormat.I

      switch(funct3) {
        is(ALUFunc3.ADD_SUB) { io.uop.aluOp := ALUOp.ADD }
        is(ALUFunc3.SLT) { io.uop.aluOp := ALUOp.SLT }
        is(ALUFunc3.SLTU) { io.uop.aluOp := ALUOp.SLTU }
        is(ALUFunc3.XOR) { io.uop.aluOp := ALUOp.XOR }
        is(ALUFunc3.OR) { io.uop.aluOp := ALUOp.OR }
        is(ALUFunc3.AND) { io.uop.aluOp := ALUOp.AND }
        is(ALUFunc3.SLL) { io.uop.aluOp := ALUOp.SLL }
        is(ALUFunc3.SRL_SRA) { // SRLI/SRAI
          io.uop.aluOp := Mux(funct7(5), ALUOp.SRA, ALUOp.SRL)
        }
      }
    }

    is(Opcodes.LOAD) {
      io.uop.opType := OpType.LOAD
      io.uop.hasImm := true.B
      io.uop.regWrite := rd =/= 0.U
      immGen.io.format := ImmFormat.I

      switch(funct3) {
        is("b000".U) { // LB
          io.uop.memWidth := MemWidth.BYTE
          io.uop.memUnsigned := false.B
        }
        is("b001".U) { // LH
          io.uop.memWidth := MemWidth.HALF
          io.uop.memUnsigned := false.B
        }
        is("b010".U) { // LW
          io.uop.memWidth := MemWidth.WORD
          io.uop.memUnsigned := false.B
        }
        is("b100".U) { // LBU
          io.uop.memWidth := MemWidth.BYTE
          io.uop.memUnsigned := true.B
        }
        is("b101".U) { // LHU
          io.uop.memWidth := MemWidth.HALF
          io.uop.memUnsigned := true.B
        }
        is("b110".U) { // LWU (RV64)
          io.uop.memWidth := MemWidth.WORD
          io.uop.memUnsigned := true.B
        }
        is("b011".U) { // LD (RV64)
          io.uop.memWidth := MemWidth.DWORD
          io.uop.memUnsigned := false.B
        }
      }
    }

    is(Opcodes.STORE) {
      io.uop.opType := OpType.STORE
      io.uop.hasImm := true.B
      io.uop.regWrite := false.B
      immGen.io.format := ImmFormat.S

      switch(funct3) {
        is("b000".U) { io.uop.memWidth := MemWidth.BYTE } // SB
        is("b001".U) { io.uop.memWidth := MemWidth.HALF } // SH
        is("b010".U) { io.uop.memWidth := MemWidth.WORD } // SW
        is("b011".U) { io.uop.memWidth := MemWidth.DWORD } // SD (RV64)
      }
    }

    is(Opcodes.LUI) {
      io.uop.opType := OpType.LUI
      io.uop.hasImm := true.B
      io.uop.regWrite := rd =/= 0.U
      immGen.io.format := ImmFormat.U
    }

    is(Opcodes.AUIPC) {
      io.uop.opType := OpType.AUIPC
      io.uop.hasImm := true.B
      io.uop.regWrite := rd =/= 0.U
      immGen.io.format := ImmFormat.U
    }
  }

  io.uop.imm := immGen.io.immediate
}
