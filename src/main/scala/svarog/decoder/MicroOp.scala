package svarog.decoder

import chisel3._
import svarog.bits.ALUOp
import svarog.memory.MemWidth

object OpType extends ChiselEnum {
  val INVALID, NOP, ALU, LOAD, STORE, BRANCH, JAL, JALR, LUI, AUIPC, SYSTEM =
    Value
}

class MicroOp(xlen: Int) extends Bundle {
  val opType = Output(OpType())
  val aluOp = Output(ALUOp())
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
}

object MicroOp {
  def getInvalid(xlen: Int): MicroOp = {
    val invalid = Wire(new MicroOp(xlen))
    invalid.opType := OpType.NOP
    invalid.aluOp := ALUOp.ADD
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
    invalid
  }
}
