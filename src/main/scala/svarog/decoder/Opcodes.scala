package svarog.decoder

import chisel3._
import chisel3.util._

object Opcodes {
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
  val MULDIV = "b0110011".U
  val MISC_MEM = "b0001111".U // FENCE, FENCE.I
}

object ALUFunc3 {
  val ADD_SUB = "b000".U
  val SLL = "b001".U
  val SLT = "b010".U
  val SLTU = "b011".U
  val XOR = "b100".U
  val SRL_SRA = "b101".U
  val OR = "b110".U
  val AND = "b111".U
}

object SRA_SLLFunc7 {
  val SRA = "b0100000".U
  val SLL = "b0000000".U
}

object CSRFunc3 {
  val RW = "b001".U
  val RS = "b010".U
  val RC = "b011".U
  val RWI = "b101".U
  val RSI = "b110".U
  val RCI = "b111".U
}

object BranchOp extends ChiselEnum {
  val INVALID, BEQ, BNE, BLT, BGE, BLTU, BGEU = Value

  def fromFunct3(funct3: UInt): BranchOp.Type = {
    // Map funct3 encoding to BranchOp enum
    // 0b000 -> BEQ, 0b001 -> BNE, 0b100 -> BLT, 0b101 -> BGE, 0b110 -> BLTU, 0b111 -> BGEU
    MuxLookup(funct3, BEQ)(
      Seq(
        "b000".U -> BEQ,
        "b001".U -> BNE,
        "b100".U -> BLT,
        "b101".U -> BGE,
        "b110".U -> BLTU,
        "b111".U -> BGEU
      )
    )
  }
}
