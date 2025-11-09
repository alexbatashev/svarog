package svarog.bits

import chisel3._
import chisel3.util._

object Opcodes extends ChiselEnum {
  val ALU_REG = "b0110011".U
  val ALU_IMM = "b0010011".U
  val LUI = "b0110111".U
  val AUIPC = "b0010111".U
  val JAL = "b1101111".U
  val JALR = "b1100111".U
  val BRANCH = "b1100011".U
  val LOAD = "b0000011".U
  val STORE = "b0100011".U
  val SYSTEM = "b1110011".U
}

object ALUFunc3 extends ChiselEnum {
  val ADD_SUB = "b000".U
  val SLL = "b001".U
  val SLT = "b010".U
  val SLTU = "b011".U
  val XOR = "b100".U
  val SRL_SRA = "b101".U
  val OR = "b110".U
  val AND = "b111".U
}

object BranchFunc3 extends ChiselEnum {
  val BEQ = "b000".U   // Branch if Equal
  val BNE = "b001".U   // Branch if Not Equal
  val BLT = "b100".U   // Branch if Less Than (signed)
  val BGE = "b101".U   // Branch if Greater or Equal (signed)
  val BLTU = "b110".U  // Branch if Less Than Unsigned
  val BGEU = "b111".U  // Branch if Greater or Equal Unsigned
}
