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
