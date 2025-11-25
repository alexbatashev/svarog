package svarog.decoder

import chisel3._
import chisel3.util._
import svarog.bits.ALUOp
import svarog.memory.MemWidth
import chisel3.util.experimental.decode.TruthTable
import svarog.bits.MulOp
import svarog.bits.DivOp

case class ZicsrInstructions(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val decoded = new MicroOp(xlen)
    val pc = Input(UInt(xlen.W))
    val instruction = Input(UInt(32.W))
  })

  io.decoded := MicroOp.getInvalid(xlen)

  val opcode = io.instruction(6, 0)
  val rd = io.instruction(11, 7)
  val rs1 = io.instruction(19, 15)
  val funct3 = io.instruction(14, 12)
  val addr = io.instruction(31, 20)

  def createMOpCommon() = {
    io.decoded.rd := rd
    io.decoded.csrAddr := addr
    io.decoded.regWrite := true.B
  }

  def createMOpReg() = {
    createMOpCommon()
    io.decoded.rs1 := rs1
  }

  def createMOpImm() = {
    createMOpCommon()
    io.decoded.hasImm := true.B
    io.decoded.imm := rs1
  }

  when(opcode === Opcodes.SYSTEM) {
    when(funct3 === CSRFunc3.RW) {
      io.decoded.opType := OpType.CSRRW
      createMOpReg()
    }.elsewhen(funct3 === CSRFunc3.RS) {
      io.decoded.opType := OpType.CSRRS
      createMOpReg()
    }.elsewhen(funct3 === CSRFunc3.RC) {
      io.decoded.opType := OpType.CSRRC
      createMOpReg()
    }.elsewhen(funct3 === CSRFunc3.RWI) {
      io.decoded.opType := OpType.CSRRW
      createMOpImm()
    }.elsewhen(funct3 === CSRFunc3.RSI) {
      io.decoded.opType := OpType.CSRRS
      createMOpImm()
    }.elsewhen(funct3 === CSRFunc3.RCI) {
      io.decoded.opType := OpType.CSRRC
      createMOpImm()
    }
  }
}
