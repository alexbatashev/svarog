package svarog.decoder

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import svarog.bits.MulOp
import svarog.bits.DivOp

object MulDivFunct3 {
  val MUL = "000"
  val MULH = "001"
  val MULHSU = "010"
  val MULHU = "011"
  val DIV = "100"
  val DIVU = "101"
  val REM = "110"
  val REMU = "111"
}

object MulDivTypeFields {
  case object mulOpType extends DecodeField[RInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: RInst): BitPat = op.funct3 match {
      case MulDivFunct3.MUL | MulDivFunct3.MULH | MulDivFunct3.MULHSU | MulDivFunct3.MULHU => BitPat(OpType.MUL)
      case MulDivFunct3.DIV | MulDivFunct3.DIVU | MulDivFunct3.REM | MulDivFunct3.REMU => BitPat(OpType.DIV)
    }
  }

  case object divOpType extends DecodeField[RInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: RInst): BitPat = BitPat(OpType.DIV)
  }
}

case object mulOp extends DecodeField[RInst, MulOp.Type] {
  def name = "mulOp"
  def chiselType = MulOp()
  override def default = BitPat(MulOp.MUL)
  def genTable(op: RInst): BitPat = {
    op.funct3 match {
      case MulDivFunct3.MUL => BitPat(MulOp.MUL)
      case MulDivFunct3.MULH => BitPat(MulOp.MULH)
      case MulDivFunct3.MULHSU => BitPat(MulOp.MULHSU)
      case MulDivFunct3.MULHU => BitPat(MulOp.MULHU)
      case _ => BitPat(MulOp.MUL)
    }
  }
}

// DecodeField for divide operation decoding
case object divOp extends DecodeField[RInst, DivOp.Type] {
  def name = "divOp"
  def chiselType = DivOp()
  override def default = BitPat(DivOp.DIV)
  def genTable(op: RInst): BitPat = {
    op.funct3 match {
      case MulDivFunct3.DIV => BitPat(DivOp.DIV)
      case MulDivFunct3.DIVU => BitPat(DivOp.DIVU)
      case MulDivFunct3.REM => BitPat(DivOp.REM)
      case MulDivFunct3.REMU => BitPat(DivOp.REMU)
      case _ => BitPat(DivOp.DIV)
    }
  }
}

case class MInstructions(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val decoded = new MicroOp(xlen)
    val pc = Input(UInt(xlen.W))
    val instruction = Input(UInt(32.W))
  })

  val mInstrs = Seq(
    RInst("0000001", MulDivFunct3.MUL, Opcodes.MULDIV),
    RInst("0000001", MulDivFunct3.MULH, Opcodes.MULDIV),
    RInst("0000001", MulDivFunct3.MULHSU, Opcodes.MULDIV),
    RInst("0000001", MulDivFunct3.MULHU, Opcodes.MULDIV),
    RInst("0000001", MulDivFunct3.DIV, Opcodes.MULDIV),
    RInst("0000001", MulDivFunct3.DIVU, Opcodes.MULDIV),
    RInst("0000001", MulDivFunct3.REM, Opcodes.MULDIV),
    RInst("0000001", MulDivFunct3.REMU, Opcodes.MULDIV),
  )

  val mTable = new DecodeTable(
    mInstrs,
    Seq(MulDivTypeFields.mulOpType, mulOp, divOp)
  )

  val mDecoded = mTable.decode(io.instruction)

  // Extract instruction fields
  val rd = io.instruction(11, 7)
  val rs1 = io.instruction(19, 15)
  val rs2 = io.instruction(24, 20)

  io.decoded := MicroOp.getInvalid(xlen)

  val mulValid = mDecoded(MulDivTypeFields.mulOpType) =/= OpType.INVALID

  // Common register fields
  io.decoded.rd := rd
  io.decoded.rs1 := rs1
  io.decoded.rs2 := rs2
  io.decoded.pc := io.pc

  when(mulValid) {
    io.decoded.opType := mDecoded(MulDivTypeFields.mulOpType)
    io.decoded.mulOp := mDecoded(mulOp)
    io.decoded.divOp := mDecoded(divOp)
    io.decoded.hasImm := false.B
    io.decoded.regWrite := true.B
  }
}
