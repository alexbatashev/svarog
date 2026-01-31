package svarog.decoder

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._

case class ZicsrInstructions(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val decoded = new MicroOp(xlen)
    val pc = Input(UInt(xlen.W))
    val instruction = Input(UInt(32.W))
  })

  // Define CSR instructions (all have opcode SYSTEM)
  val csrInstrs = Seq(
    IInst(CSRFunct3.CSRRW, Opcodes.SYSTEM),
    IInst(CSRFunct3.CSRRS, Opcodes.SYSTEM),
    IInst(CSRFunct3.CSRRC, Opcodes.SYSTEM),
    IInst(CSRFunct3.CSRRWI, Opcodes.SYSTEM),
    IInst(CSRFunct3.CSRRSI, Opcodes.SYSTEM),
    IInst(CSRFunct3.CSRRCI, Opcodes.SYSTEM)
  )

  // Create decode table
  val csrTable = new DecodeTable(
    csrInstrs,
    Seq(CSRFields.opType, CSRFields.hasImm, CSRFields.regWrite)
  )

  // Decode the instruction
  val csrDecoded = csrTable.decode(io.instruction)

  // Extract instruction fields
  val rd = io.instruction(11, 7)
  val rs1 = io.instruction(19, 15)
  val csrAddr = io.instruction(31, 20)

  // Initialize decoded output with invalid
  io.decoded := MicroOp.getInvalid(xlen)

  // Use opType to determine if a decoder matched
  val csrValid = csrDecoded(CSRFields.opType) =/= OpType.INVALID

  // Common register fields
  io.decoded.rd := rd
  io.decoded.rs1 := rs1
  io.decoded.pc := io.pc

  when(csrValid) {
    io.decoded.opType := csrDecoded(CSRFields.opType)
    io.decoded.hasImm := csrDecoded(CSRFields.hasImm)
    io.decoded.regWrite := csrDecoded(CSRFields.regWrite) && (rd =/= 0.U)
    io.decoded.csrAddr := csrAddr

    // For immediate variants, rs1 field is actually a 5-bit unsigned immediate (zimm)
    // that must be zero-extended to xlen bits
    when(csrDecoded(CSRFields.hasImm)) {
      io.decoded.imm := Cat(0.U((xlen - 5).W), rs1)
    }
  }
}
