package svarog.decoder

import chisel3._
import svarog.bits.ALUOp
import svarog.memory.MemWidth
import svarog.bits.MulOp
import svarog.bits.DivOp

object OpType extends ChiselEnum {
  val INVALID = Value
  val NOP = Value
  val ALU = Value
  val LOAD = Value
  val STORE = Value
  val BRANCH = Value
  val JAL = Value
  val JALR = Value
  val LUI = Value
  val AUIPC = Value
  val MUL = Value
  val DIV = Value
  val CSRRW = Value
  val CSRRS = Value
  val CSRRC = Value
  val SYSTEM = Value
}

class MicroOp(val xlen: Int) extends Bundle {
  val opType = Output(OpType())
  val aluOp = Output(ALUOp())
  val mulOp = Output(MulOp())
  val divOp = Output(DivOp())
  val rd = Output(UInt(5.W))
  val rs1 = Output(UInt(5.W))
  val rs2 = Output(UInt(5.W))
  val hasImm = Output(Bool())
  val imm = Output(UInt(xlen.W))
  val memWidth = Output(MemWidth.Type())
  val memUnsigned = Output(Bool())
  val branchFunc = Output(BranchOp.Type())
  val regWrite = Output(Bool())
  val pc = Output(UInt(xlen.W))
  val isEcall = Output(Bool())
  val csrAddr = Output(UInt(12.W))
}

object MicroOp {
  def getInvalid(xlen: Int): MicroOp = {
    val invalid = Wire(new MicroOp(xlen))
    invalid.opType := OpType.INVALID
    invalid.aluOp := ALUOp.ADD
    invalid.mulOp := MulOp.MUL
    invalid.divOp := DivOp.DIV
    invalid.rd := 0.U
    invalid.rs1 := 0.U
    invalid.rs2 := 0.U
    invalid.hasImm := false.B
    invalid.imm := 0.U
    invalid.memWidth := MemWidth.WORD
    invalid.memUnsigned := false.B
    invalid.branchFunc := BranchOp.INVALID
    invalid.regWrite := false.B
    invalid.pc := 0.U
    invalid.isEcall := false.B
    invalid.csrAddr := 0.U
    invalid
  }
}
