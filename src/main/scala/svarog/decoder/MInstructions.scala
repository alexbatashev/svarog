package svarog.decoder

import chisel3._
import chisel3.util._
import svarog.bits.ALUOp
import svarog.memory.MemWidth
import chisel3.util.experimental.decode.TruthTable
import svarog.bits.MulOp
import svarog.bits.DivOp

case class MInstructions(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val decoded = new MicroOp(xlen)
    val pc = Input(UInt(xlen.W))
    val instruction = Input(UInt(32.W))
  })

  io.decoded := MicroOp.getInvalid(xlen)

  val opcode = io.instruction(6, 0)
  val rd = io.instruction(11, 7)
  val rs1 = io.instruction(19, 15)
  val rs2 = io.instruction(24, 20)
  val funct3 = io.instruction(14, 12)
  val funct7 = io.instruction(31, 25)

  when (opcode === Opcodes.MULDIV && funct7 === 0b1.U) {
    io.decoded.rd := rd
    io.decoded.rs1 := rs1
    io.decoded.rs2 := rs2
    when(funct3(2) === 0.U) { // Multiply
      io.decoded.opType := OpType.MUL
      switch (funct3(1, 0)) {
        is(0b00.U) {
          io.decoded.mulOp := MulOp.MUL
        }
        is (0b01.U) {
          io.decoded.mulOp := MulOp.MULH
        }
        is (0b10.U) {
          io.decoded.mulOp := MulOp.MULHSU
        }
        is (0b11.U) {
          io.decoded.mulOp := MulOp.MULHU
        }
      }
    }.otherwise { // Divide
      io.decoded.opType := OpType.DIV
      switch (funct3(1, 0)) {
        is(0b00.U) {
          io.decoded.divOp := DivOp.DIV
        }
        is (0b01.U) {
          io.decoded.divOp := DivOp.DIVU
        }
        is (0b10.U) {
          io.decoded.divOp := DivOp.REM
        }
        is (0b11.U) {
          io.decoded.divOp := DivOp.REMU
        }
      }
    }
  }
}
