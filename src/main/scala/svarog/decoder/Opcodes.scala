package svarog.decoder

import chisel3._
import chisel3.util._
import chisel3.util.BitPat
import chisel3.util.experimental.decode._
import svarog.memory.MemWidth
import svarog.bits.MulOp
import svarog.bits.DivOp

case class RInst(val funct7: String, val funct3: String, val opcode: String) extends DecodePattern {
  require(funct7.length() == 7)
  require(funct3.length() == 3)
  require(opcode.length() == 7)

  private val reg = "?????"

  def bitPat: BitPat = BitPat("b" + funct7 + reg + reg + funct3 + reg + opcode)
}

case class IInst(val funct3: String, val opcode: String) extends DecodePattern {
  require(funct3.length() == 3)
  require(opcode.length() == 7)

  private val imm = "????????????"
  private val reg = "?????"

  def bitPat: BitPat = BitPat("b" + imm + reg + funct3 + reg + opcode)
}

case class SInst(val funct3: String, val opcode: String) extends DecodePattern {
  require(funct3.length() == 3)
  require(opcode.length() == 7)

  private val immHi = "???????"
  private val immLo = "?????"
  private val reg = "?????"

  def bitPat: BitPat = BitPat("b" + immHi + reg + reg + funct3 + immLo + opcode)
}

case class UInst(val opcode: String) extends DecodePattern {
  require(opcode.length() == 7)

  private val imm = "????????????????????"
  private val reg = "?????"

  def bitPat: BitPat = BitPat("b" + imm + reg + opcode)
}

case class BInst(val funct3: String, val opcode: String) extends DecodePattern {
  require(funct3.length() == 3)
  require(opcode.length() == 7)

  private val immHi = "???????"
  private val immLo = "?????"
  private val reg = "?????"

  def bitPat: BitPat = BitPat("b" + immHi + reg + reg + funct3 + immLo + opcode)
}

case class JInst(val opcode: String) extends DecodePattern {
  require(opcode.length() == 7)

  private val imm = "????????????????????"
  private val reg = "?????"

  def bitPat: BitPat = BitPat("b" + imm + reg + opcode)
}

// Special pattern for ECALL/EBREAK - they have exact bit patterns
case class SystemInst(val imm12: String) extends DecodePattern {
  require(imm12.length() == 12)

  private val opcode = Opcodes.SYSTEM
  private val funct3 = "000"
  private val reg = "00000"

  def bitPat: BitPat = BitPat("b" + imm12 + reg + funct3 + reg + opcode)
}

// DecodeField implementations for R-type instructions
object RTypeFields {
  case object opType extends DecodeField[RInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: RInst): BitPat = BitPat(OpType.ALU)
  }

  case object hasImm extends DecodeField[RInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: RInst): BitPat = BitPat(0.U(1.W)) // R-type never has immediate
  }

  case object regWrite extends DecodeField[RInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: RInst): BitPat = BitPat(1.U(1.W)) // R-type always writes (will gate by rd != 0 later)
  }
}

// DecodeField implementations for I-type instructions (non-shift)
object ITypeFields {
  case object opType extends DecodeField[IInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: IInst): BitPat = BitPat(OpType.ALU)
  }

  case object hasImm extends DecodeField[IInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(1.U(1.W)) // I-type always has immediate
  }

  case object regWrite extends DecodeField[IInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(1.U(1.W))
  }
}

// DecodeField implementations for U-type instructions (LUI, AUIPC)
object UTypeFields {
  case object opType extends DecodeField[UInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: UInst): BitPat = {
      op.opcode match {
        case Opcodes.LUI => BitPat(OpType.LUI)
        case Opcodes.AUIPC => BitPat(OpType.AUIPC)
        case _ => BitPat(OpType.INVALID)
      }
    }
  }

  case object hasImm extends DecodeField[UInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: UInst): BitPat = BitPat(1.U(1.W))
  }

  case object regWrite extends DecodeField[UInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: UInst): BitPat = BitPat(1.U(1.W))
  }
}

// DecodeField implementations for J-type instructions (JAL)
object JTypeFields {
  case object opType extends DecodeField[JInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: JInst): BitPat = BitPat(OpType.JAL)
  }

  case object hasImm extends DecodeField[JInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: JInst): BitPat = BitPat(1.U(1.W))
  }

  case object regWrite extends DecodeField[JInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: JInst): BitPat = BitPat(1.U(1.W))
  }
}

// DecodeField implementations for B-type instructions (branches)
object BTypeFields {
  case object opType extends DecodeField[BInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: BInst): BitPat = BitPat(OpType.BRANCH)
  }

  case object branchFunc extends DecodeField[BInst, BranchOp.Type] {
    def name = "branchFunc"
    def chiselType = BranchOp()
    override def default = BitPat(BranchOp.INVALID)
    def genTable(op: BInst): BitPat = {
      op.funct3 match {
        case BranchFunct3.BEQ => BitPat(BranchOp.BEQ)
        case BranchFunct3.BNE => BitPat(BranchOp.BNE)
        case BranchFunct3.BLT => BitPat(BranchOp.BLT)
        case BranchFunct3.BGE => BitPat(BranchOp.BGE)
        case BranchFunct3.BLTU => BitPat(BranchOp.BLTU)
        case BranchFunct3.BGEU => BitPat(BranchOp.BGEU)
        case _ => BitPat(BranchOp.INVALID)
      }
    }
  }

  case object hasImm extends DecodeField[BInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: BInst): BitPat = BitPat(1.U(1.W))
  }

  case object regWrite extends DecodeField[BInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: BInst): BitPat = BitPat(0.U(1.W)) // Branches don't write to registers
  }
}

// DecodeField implementations for S-type instructions (stores)
object STypeFields {
  case object opType extends DecodeField[SInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: SInst): BitPat = BitPat(OpType.STORE)
  }

  case object memWidth extends DecodeField[SInst, MemWidth.Type] {
    def name = "memWidth"
    def chiselType = MemWidth()
    override def default = BitPat(MemWidth.WORD)
    def genTable(op: SInst): BitPat = {
      op.funct3 match {
        case StoreFunct3.SB => BitPat(MemWidth.BYTE)
        case StoreFunct3.SH => BitPat(MemWidth.HALF)
        case StoreFunct3.SW => BitPat(MemWidth.WORD)
        case _ => BitPat(MemWidth.WORD)
      }
    }
  }

  case object hasImm extends DecodeField[SInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: SInst): BitPat = BitPat(1.U(1.W))
  }

  case object regWrite extends DecodeField[SInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: SInst): BitPat = BitPat(0.U(1.W)) // Stores don't write to registers
  }
}

// DecodeField implementations for Load instructions (I-type with memory operations)
object LoadFields {
  case object opType extends DecodeField[IInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: IInst): BitPat = BitPat(OpType.LOAD)
  }

  case object memWidth extends DecodeField[IInst, MemWidth.Type] {
    def name = "memWidth"
    def chiselType = MemWidth()
    override def default = BitPat(MemWidth.WORD)
    def genTable(op: IInst): BitPat = {
      op.funct3 match {
        case LoadFunct3.LB => BitPat(MemWidth.BYTE)
        case LoadFunct3.LH => BitPat(MemWidth.HALF)
        case LoadFunct3.LW => BitPat(MemWidth.WORD)
        case LoadFunct3.LBU => BitPat(MemWidth.BYTE)
        case LoadFunct3.LHU => BitPat(MemWidth.HALF)
        case _ => BitPat(MemWidth.WORD)
      }
    }
  }

  case object memUnsigned extends DecodeField[IInst, Bool] {
    def name = "memUnsigned"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = {
      op.funct3 match {
        case LoadFunct3.LBU => BitPat(1.U(1.W))
        case LoadFunct3.LHU => BitPat(1.U(1.W))
        case _ => BitPat(0.U(1.W))
      }
    }
  }

  case object hasImm extends DecodeField[IInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(1.U(1.W))
  }

  case object regWrite extends DecodeField[IInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(1.U(1.W))
  }
}

// DecodeField implementations for JALR (I-type with special handling)
object JALRFields {
  case object opType extends DecodeField[IInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: IInst): BitPat = BitPat(OpType.JALR)
  }

  case object hasImm extends DecodeField[IInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(1.U(1.W))
  }

  case object regWrite extends DecodeField[IInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(1.U(1.W))
  }
}

// DecodeField implementations for FENCE instructions
object FenceFields {
  case object opType extends DecodeField[IInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: IInst): BitPat = {
      op.funct3 match {
        case FenceFunct3.FENCE => BitPat(OpType.FENCE)
        case FenceFunct3.FENCE_I => BitPat(OpType.FENCE_I)
        case _ => BitPat(OpType.INVALID)
      }
    }
  }

  case object hasImm extends DecodeField[IInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(0.U(1.W))
  }

  case object regWrite extends DecodeField[IInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(0.U(1.W))
  }
}

// DecodeField implementations for CSR instructions
object CSRFields {
  case object opType extends DecodeField[IInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: IInst): BitPat = {
      op.funct3 match {
        case CSRFunct3.CSRRW | CSRFunct3.CSRRWI => BitPat(OpType.CSRRW)
        case CSRFunct3.CSRRS | CSRFunct3.CSRRSI => BitPat(OpType.CSRRS)
        case CSRFunct3.CSRRC | CSRFunct3.CSRRCI => BitPat(OpType.CSRRC)
        case _ => BitPat(OpType.INVALID)
      }
    }
  }

  case object hasImm extends DecodeField[IInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = {
      op.funct3 match {
        case CSRFunct3.CSRRWI | CSRFunct3.CSRRSI | CSRFunct3.CSRRCI => BitPat(1.U(1.W))
        case _ => BitPat(0.U(1.W))
      }
    }
  }

  case object regWrite extends DecodeField[IInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: IInst): BitPat = BitPat(1.U(1.W))
  }
}

// DecodeField implementations for System instructions (ECALL, EBREAK, MRET)
object SystemFields {
  case object opType extends DecodeField[SystemInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: SystemInst): BitPat = {
      op.imm12 match {
        case SystemImm12.ECALL => BitPat(OpType.ECALL)
        case SystemImm12.EBREAK => BitPat(OpType.EBREAK)
        case SystemImm12.MRET => BitPat(OpType.MRET)
        case _ => BitPat(OpType.INVALID)
      }
    }
  }

  case object hasImm extends DecodeField[SystemInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: SystemInst): BitPat = BitPat(0.U(1.W))
  }

  case object regWrite extends DecodeField[SystemInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: SystemInst): BitPat = BitPat(0.U(1.W))
  }
}

object Opcodes {
  val ALU_REG = "0110011"
  val ALU_IMM = "0010011"
  val LUI = "0110111"
  val AUIPC = "0010111"
  val JAL = "1101111"
  val JALR = "1100111"
  val BRANCH = "1100011"
  val LOAD = "0000011"
  val STORE = "0100011"
  val SYSTEM = "1110011"
  val MULDIV = "0110011"
  val MISC_MEM = "0001111" // FENCE, FENCE.I
}

object LoadFunct3 {
  val LB = "000"
  val LH = "001"
  val LW = "010"
  val LBU = "100"
  val LHU = "101"
}

object StoreFunct3 {
  val SB = "000"
  val SH = "001"
  val SW = "010"
}

object BranchFunct3 {
  val BEQ = "000"
  val BNE = "001"
  val BLT = "100"
  val BGE = "101"
  val BLTU = "110"
  val BGEU = "111"
}

object JALRFunct3 {
  val JALR = "000"
}

object SystemImm12 {
  val ECALL = "000000000000"
  val EBREAK = "000000000001"
  val MRET = "001100000010"
}

object CSRFunct3 {
  val CSRRW = "001"
  val CSRRS = "010"
  val CSRRC = "011"
  val CSRRWI = "101"
  val CSRRSI = "110"
  val CSRRCI = "111"
}

object FenceFunct3 {
  val FENCE = "000"
  val FENCE_I = "001"
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
