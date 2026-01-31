package svarog.decoder

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

import svarog.bits.ALUOp
import svarog.memory.MemWidth
import svarog.bits.MulOp
import svarog.bits.DivOp

object Funct7 {
  val DEFAULT = "0000000"
  val ALT = "0100000"
}

object Funct3 {
  val ADD_SUB = "000"
  val SLL = "001"
  val SLT = "010"
  val SLTU = "011"
  val XOR = "100"
  val SRL_SRA = "101"
  val OR = "110"
  val AND = "111"
}

// Special I-type for shift instructions that need to check imm[10] (bit 30)
case class ShiftIInst(val funct7: String, val funct3: String, val opcode: String) extends DecodePattern {
  require(funct7.length() == 7)
  require(funct3.length() == 3)
  require(opcode.length() == 7)

  private val shamt = "?????"
  private val reg = "?????"

  def bitPat: BitPat = BitPat("b" + funct7 + shamt + reg + funct3 + reg + opcode)
}

object LoadFields {
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
}

case object aluRegOp extends DecodeField[RInst, ALUOp.Type] {
  def name = "aluRegOp"
  def chiselType = ALUOp()
  override def default = BitPat(ALUOp.ADD)
  def genTable(op: RInst): BitPat = {
    (op.funct7, op.funct3) match {
      case (Funct7.DEFAULT, Funct3.ADD_SUB) => BitPat(ALUOp.ADD)
      case (Funct7.ALT, Funct3.ADD_SUB) => BitPat(ALUOp.SUB)
      case (_, Funct3.SLL) => BitPat(ALUOp.SLL)
      case (_, Funct3.SLT) => BitPat(ALUOp.SLT)
      case (_, Funct3.SLTU) => BitPat(ALUOp.SLTU)
      case (_, Funct3.XOR) => BitPat(ALUOp.XOR)
      case (Funct7.DEFAULT, Funct3.SRL_SRA) => BitPat(ALUOp.SRL)
      case (Funct7.ALT, Funct3.SRL_SRA) => BitPat(ALUOp.SRA)
      case (_, Funct3.OR) => BitPat(ALUOp.OR)
      case (_, Funct3.AND) => BitPat(ALUOp.AND)
      case _ => BitPat(ALUOp.ADD)
    }
  }
}

case object aluImmOp extends DecodeField[IInst, ALUOp.Type] {
  def name = "aluImmOp"
  def chiselType = ALUOp()
  override def default = BitPat(ALUOp.ADD)
  def genTable(op: IInst): BitPat = {
    op.funct3 match {
      case Funct3.ADD_SUB => BitPat(ALUOp.ADD) // ADDI (no SUBI)
      case Funct3.SLT => BitPat(ALUOp.SLT)
      case Funct3.SLTU => BitPat(ALUOp.SLTU)
      case Funct3.XOR => BitPat(ALUOp.XOR)
      case Funct3.OR => BitPat(ALUOp.OR)
      case Funct3.AND => BitPat(ALUOp.AND)
      case _ => BitPat(ALUOp.ADD)
    }
  }
}

// DecodeField implementations for I-type shift instructions
object ShiftITypeFields {
  case object opType extends DecodeField[ShiftIInst, OpType.Type] {
    def name = "opType"
    def chiselType = OpType()
    override def default = BitPat(OpType.INVALID)
    def genTable(op: ShiftIInst): BitPat = BitPat(OpType.ALU)
  }

  case object aluOp extends DecodeField[ShiftIInst, ALUOp.Type] {
    def name = "aluOp"
    def chiselType = ALUOp()
    override def default = BitPat(ALUOp.ADD)
    def genTable(op: ShiftIInst): BitPat = {
      (op.funct7, op.funct3) match {
        case (Funct7.DEFAULT, Funct3.SLL) => BitPat(ALUOp.SLL)
        case (Funct7.DEFAULT, Funct3.SRL_SRA) => BitPat(ALUOp.SRL)
        case (Funct7.ALT, Funct3.SRL_SRA) => BitPat(ALUOp.SRA)
        case _ => BitPat(ALUOp.ADD)
      }
    }
  }

  case object hasImm extends DecodeField[ShiftIInst, Bool] {
    def name = "hasImm"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: ShiftIInst): BitPat = BitPat(1.U(1.W))
  }

  case object regWrite extends DecodeField[ShiftIInst, Bool] {
    def name = "regWrite"
    def chiselType = Bool()
    override def default = BitPat(0.U(1.W))
    def genTable(op: ShiftIInst): BitPat = BitPat(1.U(1.W))
  }
}

case class BaseInstructions(xlen: Int) extends Module {
  require(xlen >= 32, "BaseInstructions requires XLEN >= 32")

  val io = IO(new Bundle {
    val immGen = Flipped(new ImmGenIO(xlen))
    val decoded = new MicroOp(xlen)
    val pc = Input(UInt(xlen.W))
    val instruction = Input(UInt(32.W))
  })

  val rTypeInstrs = Seq(
    RInst(Funct7.DEFAULT, Funct3.ADD_SUB, Opcodes.ALU_REG), // Add
    RInst(Funct7.ALT, Funct3.ADD_SUB, Opcodes.ALU_REG), // Sub
    RInst(Funct7.DEFAULT, Funct3.SLL, Opcodes.ALU_REG),
    RInst(Funct7.DEFAULT, Funct3.SLT, Opcodes.ALU_REG),
    RInst(Funct7.DEFAULT, Funct3.SLTU, Opcodes.ALU_REG),
    RInst(Funct7.DEFAULT, Funct3.XOR, Opcodes.ALU_REG),
    RInst(Funct7.DEFAULT, Funct3.SRL_SRA, Opcodes.ALU_REG), // SRL
    RInst(Funct7.ALT, Funct3.SRL_SRA, Opcodes.ALU_REG), // SRA
    RInst(Funct7.DEFAULT, Funct3.OR, Opcodes.ALU_REG),
    RInst(Funct7.DEFAULT, Funct3.AND, Opcodes.ALU_REG),
  )

  val iTypeInstrs = Seq(
    IInst(Funct3.ADD_SUB, Opcodes.ALU_IMM),
    IInst(Funct3.SLT, Opcodes.ALU_IMM),
    IInst(Funct3.SLTU, Opcodes.ALU_IMM),
    IInst(Funct3.XOR, Opcodes.ALU_IMM),
    IInst(Funct3.OR, Opcodes.ALU_IMM),
    IInst(Funct3.AND, Opcodes.ALU_IMM),
    IInst(LoadFunct3.LB, Opcodes.LOAD),
    IInst(LoadFunct3.LH, Opcodes.LOAD),
    IInst(LoadFunct3.LW, Opcodes.LOAD),
    IInst(LoadFunct3.LBU, Opcodes.LOAD),
    IInst(LoadFunct3.LHU, Opcodes.LOAD),
    IInst(JALRFunct3.JALR, Opcodes.JALR),
  )

  val shiftITypeInstrs = Seq(
    ShiftIInst(Funct7.DEFAULT, Funct3.SLL, Opcodes.ALU_IMM),     // SLLI
    ShiftIInst(Funct7.DEFAULT, Funct3.SRL_SRA, Opcodes.ALU_IMM), // SRLI
    ShiftIInst(Funct7.ALT, Funct3.SRL_SRA, Opcodes.ALU_IMM),     // SRAI
  )

  val uTypeInstrs = Seq(
    UInst(Opcodes.LUI),
    UInst(Opcodes.AUIPC),
  )

  val jTypeInstrs = Seq(
    JInst(Opcodes.JAL),
  )

  val branchInstrs = Seq(
    BInst(BranchFunct3.BEQ, Opcodes.BRANCH),
    BInst(BranchFunct3.BNE, Opcodes.BRANCH),
    BInst(BranchFunct3.BLT, Opcodes.BRANCH),
    BInst(BranchFunct3.BGE, Opcodes.BRANCH),
    BInst(BranchFunct3.BLTU, Opcodes.BRANCH),
    BInst(BranchFunct3.BGEU, Opcodes.BRANCH),
  )

  val storeInstrs = Seq(
    SInst(StoreFunct3.SB, Opcodes.STORE),
    SInst(StoreFunct3.SH, Opcodes.STORE),
    SInst(StoreFunct3.SW, Opcodes.STORE),
  )

  val systemInstrs = Seq(
    SystemInst(SystemImm12.ECALL),
    SystemInst(SystemImm12.EBREAK),
    SystemInst(SystemImm12.MRET),
  )

  val fenceInstrs = Seq(
    IInst(FenceFunct3.FENCE, Opcodes.MISC_MEM),
    IInst(FenceFunct3.FENCE_I, Opcodes.MISC_MEM),
  )

  // Create decode tables
  val rTable = new DecodeTable(
    rTypeInstrs,
    Seq(RTypeFields.opType, aluRegOp)
  )

  val iTable = new DecodeTable(
    iTypeInstrs,
    Seq(ITypeFields.opType, LoadFields.memWidth, LoadFields.memUnsigned, aluImmOp)
  )

  val shiftITable = new DecodeTable(
    shiftITypeInstrs,
    Seq(ShiftITypeFields.opType, ShiftITypeFields.aluOp)
  )

  val uTable = new DecodeTable(
    uTypeInstrs,
    Seq(UTypeFields.opType)
  )

  val jTable = new DecodeTable(
    jTypeInstrs,
    Seq(JTypeFields.opType)
  )

  val branchTable = new DecodeTable(
    branchInstrs,
    Seq(BTypeFields.opType, BTypeFields.branchFunc)
  )

  val storeTable = new DecodeTable(
    storeInstrs,
    Seq(STypeFields.opType, STypeFields.memWidth)
  )

  val systemTable = new DecodeTable(
    systemInstrs,
    Seq(SystemFields.opType, SystemFields.hasImm, SystemFields.regWrite)
  )

  val fenceTable = new DecodeTable(
    fenceInstrs,
    Seq(FenceFields.opType)
  )

  // Decode the instruction
  val rTypeDecoded = rTable.decode(io.instruction)
  val iTypeDecoded = iTable.decode(io.instruction)
  val shiftITypeDecoded = shiftITable.decode(io.instruction)
  val uTypeDecoded = uTable.decode(io.instruction)
  val jTypeDecoded = jTable.decode(io.instruction)
  val branchTypeDecoded = branchTable.decode(io.instruction)
  val storeTypeDecoded = storeTable.decode(io.instruction)
  val systemTypeDecoded = systemTable.decode(io.instruction)
  val fenceTypeDecoded = fenceTable.decode(io.instruction)

  // Extract instruction fields
  val opcode = io.instruction(6, 0)
  val rd = io.instruction(11, 7)
  val rs1 = io.instruction(19, 15)
  val rs2 = io.instruction(24, 20)

  // Initialize decoded output with invalid
  io.decoded := MicroOp.getInvalid(xlen)

  // Use opType to determine if a decoder matched (opType != INVALID means match)
  val rTypeValid = rTypeDecoded(RTypeFields.opType) =/= OpType.INVALID
  val shiftITypeValid = shiftITypeDecoded(ShiftITypeFields.opType) =/= OpType.INVALID
  val iTypeValid = iTypeDecoded(ITypeFields.opType) =/= OpType.INVALID
  val uTypeValid = uTypeDecoded(UTypeFields.opType) =/= OpType.INVALID
  val jTypeValid = jTypeDecoded(JTypeFields.opType) =/= OpType.INVALID
  val branchTypeValid = branchTypeDecoded(BTypeFields.opType) =/= OpType.INVALID
  val storeTypeValid = storeTypeDecoded(STypeFields.opType) =/= OpType.INVALID
  val systemTypeValid = systemTypeDecoded(SystemFields.opType) =/= OpType.INVALID
  val fenceTypeValid = fenceTypeDecoded(FenceFields.opType) =/= OpType.INVALID

  // Common register fields
  io.decoded.rd := rd
  io.decoded.rs1 := rs1
  io.decoded.pc := io.pc

  // Default immediate format (will be overridden per instruction type)
  io.immGen.instruction := io.instruction
  io.immGen.format := ImmFormat.I

  when(rTypeValid) {
    io.decoded.opType := rTypeDecoded(RTypeFields.opType)
    io.decoded.aluOp := rTypeDecoded(aluRegOp)
    io.decoded.hasImm := false.B
    io.decoded.regWrite := true.B
    io.decoded.rs2 := rs2
  }.elsewhen(shiftITypeValid) {
    io.decoded.opType := shiftITypeDecoded(ShiftITypeFields.opType)
    io.decoded.aluOp := shiftITypeDecoded(ShiftITypeFields.aluOp)
    io.decoded.hasImm := true.B
    io.decoded.regWrite := true.B
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.I
  }.elsewhen(iTypeValid) {
    io.decoded.opType := iTypeDecoded(ITypeFields.opType)
    io.decoded.aluOp := iTypeDecoded(aluImmOp)
    io.decoded.hasImm := true.B
    io.decoded.regWrite := true.B
    io.decoded.memWidth := iTypeDecoded(LoadFields.memWidth)
    io.decoded.memUnsigned := iTypeDecoded(LoadFields.memUnsigned)
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.I
  }.elsewhen(storeTypeValid) {
    io.decoded.opType := storeTypeDecoded(STypeFields.opType)
    io.decoded.memWidth := storeTypeDecoded(STypeFields.memWidth)
    io.decoded.hasImm := true.B
    io.decoded.regWrite := false.B
    io.decoded.rs2 := rs2
    io.immGen.format := ImmFormat.S
  }.elsewhen(branchTypeValid) {
    io.decoded.opType := branchTypeDecoded(BTypeFields.opType)
    io.decoded.branchFunc := branchTypeDecoded(BTypeFields.branchFunc)
    io.decoded.hasImm := true.B
    io.decoded.regWrite := false.B
    io.decoded.rs2 := rs2
    io.immGen.format := ImmFormat.B
  }.elsewhen(jTypeValid) {
    io.decoded.opType := jTypeDecoded(JTypeFields.opType)
    io.decoded.hasImm := true.B
    io.decoded.regWrite := true.B
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.J
  }.elsewhen(uTypeValid) {
    io.decoded.opType := uTypeDecoded(UTypeFields.opType)
    io.decoded.hasImm := true.B
    io.decoded.regWrite := true.B
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.U
  }.elsewhen(systemTypeValid) {
    io.decoded.opType := systemTypeDecoded(SystemFields.opType)
    io.decoded.hasImm := systemTypeDecoded(SystemFields.hasImm)
    io.decoded.regWrite := systemTypeDecoded(SystemFields.regWrite)
    io.decoded.rs2 := 0.U
  }.elsewhen(fenceTypeValid) {
    io.decoded.opType := fenceTypeDecoded(FenceFields.opType)
    io.decoded.hasImm := false.B
    io.decoded.regWrite := false.B
    io.decoded.rs2 := 0.U
  }

  io.decoded.imm := io.immGen.immediate
}
