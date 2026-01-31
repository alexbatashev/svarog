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

  // Define R-type instructions
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

  // Define I-type instructions (non-shift)
  val iTypeInstrs = Seq(
    IInst(Funct3.ADD_SUB, Opcodes.ALU_IMM), // ADDI
    IInst(Funct3.SLT, Opcodes.ALU_IMM),     // SLTI
    IInst(Funct3.SLTU, Opcodes.ALU_IMM),    // SLTIU
    IInst(Funct3.XOR, Opcodes.ALU_IMM),     // XORI
    IInst(Funct3.OR, Opcodes.ALU_IMM),      // ORI
    IInst(Funct3.AND, Opcodes.ALU_IMM),     // ANDI
  )

  // Define I-type shift instructions (need funct7 to distinguish)
  val shiftITypeInstrs = Seq(
    ShiftIInst(Funct7.DEFAULT, Funct3.SLL, Opcodes.ALU_IMM),     // SLLI
    ShiftIInst(Funct7.DEFAULT, Funct3.SRL_SRA, Opcodes.ALU_IMM), // SRLI
    ShiftIInst(Funct7.ALT, Funct3.SRL_SRA, Opcodes.ALU_IMM),     // SRAI
  )

  // Define U-type instructions (LUI, AUIPC)
  val uTypeInstrs = Seq(
    UInst(Opcodes.LUI),
    UInst(Opcodes.AUIPC),
  )

  // Define J-type instructions (JAL)
  val jTypeInstrs = Seq(
    JInst(Opcodes.JAL),
  )

  // Define JALR instruction (I-type)
  val jalrInstrs = Seq(
    IInst(JALRFunct3.JALR, Opcodes.JALR),
  )

  // Define Branch instructions (B-type)
  val branchInstrs = Seq(
    BInst(BranchFunct3.BEQ, Opcodes.BRANCH),
    BInst(BranchFunct3.BNE, Opcodes.BRANCH),
    BInst(BranchFunct3.BLT, Opcodes.BRANCH),
    BInst(BranchFunct3.BGE, Opcodes.BRANCH),
    BInst(BranchFunct3.BLTU, Opcodes.BRANCH),
    BInst(BranchFunct3.BGEU, Opcodes.BRANCH),
  )

  // Define Load instructions (I-type)
  val loadInstrs = Seq(
    IInst(LoadFunct3.LB, Opcodes.LOAD),
    IInst(LoadFunct3.LH, Opcodes.LOAD),
    IInst(LoadFunct3.LW, Opcodes.LOAD),
    IInst(LoadFunct3.LBU, Opcodes.LOAD),
    IInst(LoadFunct3.LHU, Opcodes.LOAD),
  )

  // Define Store instructions (S-type)
  val storeInstrs = Seq(
    SInst(StoreFunct3.SB, Opcodes.STORE),
    SInst(StoreFunct3.SH, Opcodes.STORE),
    SInst(StoreFunct3.SW, Opcodes.STORE),
  )

  // Define System instructions (ECALL, EBREAK, MRET)
  val systemInstrs = Seq(
    SystemInst(SystemImm12.ECALL),
    SystemInst(SystemImm12.EBREAK),
    SystemInst(SystemImm12.MRET),
  )

  // Define FENCE instructions (I-type)
  val fenceInstrs = Seq(
    IInst(FenceFunct3.FENCE, Opcodes.MISC_MEM),
    IInst(FenceFunct3.FENCE_I, Opcodes.MISC_MEM),
  )

  // Create decode tables
  val rTable = new DecodeTable(
    rTypeInstrs,
    Seq(RTypeFields.opType, aluRegOp, RTypeFields.hasImm, RTypeFields.regWrite)
  )

  val iTable = new DecodeTable(
    iTypeInstrs,
    Seq(ITypeFields.opType, aluImmOp, ITypeFields.hasImm, ITypeFields.regWrite)
  )

  val shiftITable = new DecodeTable(
    shiftITypeInstrs,
    Seq(ShiftITypeFields.opType, ShiftITypeFields.aluOp, ShiftITypeFields.hasImm, ShiftITypeFields.regWrite)
  )

  val uTable = new DecodeTable(
    uTypeInstrs,
    Seq(UTypeFields.opType, UTypeFields.hasImm, UTypeFields.regWrite)
  )

  val jTable = new DecodeTable(
    jTypeInstrs,
    Seq(JTypeFields.opType, JTypeFields.hasImm, JTypeFields.regWrite)
  )

  val jalrTable = new DecodeTable(
    jalrInstrs,
    Seq(JALRFields.opType, JALRFields.hasImm, JALRFields.regWrite)
  )

  val branchTable = new DecodeTable(
    branchInstrs,
    Seq(BTypeFields.opType, BTypeFields.branchFunc, BTypeFields.hasImm, BTypeFields.regWrite)
  )

  val loadTable = new DecodeTable(
    loadInstrs,
    Seq(LoadFields.opType, LoadFields.memWidth, LoadFields.memUnsigned, LoadFields.hasImm, LoadFields.regWrite)
  )

  val storeTable = new DecodeTable(
    storeInstrs,
    Seq(STypeFields.opType, STypeFields.memWidth, STypeFields.hasImm, STypeFields.regWrite)
  )

  val systemTable = new DecodeTable(
    systemInstrs,
    Seq(SystemFields.opType, SystemFields.hasImm, SystemFields.regWrite)
  )

  val fenceTable = new DecodeTable(
    fenceInstrs,
    Seq(FenceFields.opType, FenceFields.hasImm, FenceFields.regWrite)
  )

  // Decode the instruction
  val rTypeDecoded = rTable.decode(io.instruction)
  val iTypeDecoded = iTable.decode(io.instruction)
  val shiftITypeDecoded = shiftITable.decode(io.instruction)
  val uTypeDecoded = uTable.decode(io.instruction)
  val jTypeDecoded = jTable.decode(io.instruction)
  val jalrTypeDecoded = jalrTable.decode(io.instruction)
  val branchTypeDecoded = branchTable.decode(io.instruction)
  val loadTypeDecoded = loadTable.decode(io.instruction)
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
  val jalrTypeValid = jalrTypeDecoded(JALRFields.opType) =/= OpType.INVALID
  val branchTypeValid = branchTypeDecoded(BTypeFields.opType) =/= OpType.INVALID
  val loadTypeValid = loadTypeDecoded(LoadFields.opType) =/= OpType.INVALID
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
    io.decoded.hasImm := rTypeDecoded(RTypeFields.hasImm)
    io.decoded.regWrite := rTypeDecoded(RTypeFields.regWrite) && (rd =/= 0.U)
    io.decoded.rs2 := rs2
  }.elsewhen(shiftITypeValid) {
    io.decoded.opType := shiftITypeDecoded(ShiftITypeFields.opType)
    io.decoded.aluOp := shiftITypeDecoded(ShiftITypeFields.aluOp)
    io.decoded.hasImm := shiftITypeDecoded(ShiftITypeFields.hasImm)
    io.decoded.regWrite := shiftITypeDecoded(ShiftITypeFields.regWrite) && (rd =/= 0.U)
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.I
  }.elsewhen(iTypeValid) {
    io.decoded.opType := iTypeDecoded(ITypeFields.opType)
    io.decoded.aluOp := iTypeDecoded(aluImmOp)
    io.decoded.hasImm := iTypeDecoded(ITypeFields.hasImm)
    io.decoded.regWrite := iTypeDecoded(ITypeFields.regWrite) && (rd =/= 0.U)
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.I
  }.elsewhen(loadTypeValid) {
    io.decoded.opType := loadTypeDecoded(LoadFields.opType)
    io.decoded.memWidth := loadTypeDecoded(LoadFields.memWidth)
    io.decoded.memUnsigned := loadTypeDecoded(LoadFields.memUnsigned)
    io.decoded.hasImm := loadTypeDecoded(LoadFields.hasImm)
    io.decoded.regWrite := loadTypeDecoded(LoadFields.regWrite) && (rd =/= 0.U)
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.I
  }.elsewhen(storeTypeValid) {
    io.decoded.opType := storeTypeDecoded(STypeFields.opType)
    io.decoded.memWidth := storeTypeDecoded(STypeFields.memWidth)
    io.decoded.hasImm := storeTypeDecoded(STypeFields.hasImm)
    io.decoded.regWrite := storeTypeDecoded(STypeFields.regWrite)
    io.decoded.rs2 := rs2
    io.immGen.format := ImmFormat.S
  }.elsewhen(branchTypeValid) {
    io.decoded.opType := branchTypeDecoded(BTypeFields.opType)
    io.decoded.branchFunc := branchTypeDecoded(BTypeFields.branchFunc)
    io.decoded.hasImm := branchTypeDecoded(BTypeFields.hasImm)
    io.decoded.regWrite := branchTypeDecoded(BTypeFields.regWrite)
    io.decoded.rs2 := rs2
    io.immGen.format := ImmFormat.B
  }.elsewhen(jalrTypeValid) {
    io.decoded.opType := jalrTypeDecoded(JALRFields.opType)
    io.decoded.hasImm := jalrTypeDecoded(JALRFields.hasImm)
    io.decoded.regWrite := jalrTypeDecoded(JALRFields.regWrite) && (rd =/= 0.U)
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.I
  }.elsewhen(jTypeValid) {
    io.decoded.opType := jTypeDecoded(JTypeFields.opType)
    io.decoded.hasImm := jTypeDecoded(JTypeFields.hasImm)
    io.decoded.regWrite := jTypeDecoded(JTypeFields.regWrite) && (rd =/= 0.U)
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.J
  }.elsewhen(uTypeValid) {
    io.decoded.opType := uTypeDecoded(UTypeFields.opType)
    io.decoded.hasImm := uTypeDecoded(UTypeFields.hasImm)
    io.decoded.regWrite := uTypeDecoded(UTypeFields.regWrite) && (rd =/= 0.U)
    io.decoded.rs2 := 0.U
    io.immGen.format := ImmFormat.U
  }.elsewhen(systemTypeValid) {
    io.decoded.opType := systemTypeDecoded(SystemFields.opType)
    io.decoded.hasImm := systemTypeDecoded(SystemFields.hasImm)
    io.decoded.regWrite := systemTypeDecoded(SystemFields.regWrite)
    io.decoded.rs2 := 0.U
  }.elsewhen(fenceTypeValid) {
    io.decoded.opType := fenceTypeDecoded(FenceFields.opType)
    io.decoded.hasImm := fenceTypeDecoded(FenceFields.hasImm)
    io.decoded.regWrite := fenceTypeDecoded(FenceFields.regWrite)
    io.decoded.rs2 := 0.U
  }

  io.decoded.imm := io.immGen.immediate
}
