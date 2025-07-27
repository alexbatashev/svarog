package svarog.bits

import chisel3._
import chisel3.util._

object ImmFormat extends ChiselEnum {
  val I, S, B, U, J = Value
}

class ImmGen(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val format = Input(ImmFormat())
    val immediate = Output(UInt(xlen.W))
  })

  io.immediate := 0.U

  val signBit = io.instruction(31)
  val inst = io.instruction

  switch (io.format) {
    is(ImmFormat.I) {
      // Sign-extend from bit 11 of the immediate.
      io.immediate := Cat(Fill(xlen - 12, signBit), inst(31, 20)).asUInt
    }

    is(ImmFormat.S) {
      val imm_11_5 = inst(31, 25)
      val imm_4_0  = inst(11, 7)
      // Sign-extend from bit 11 of the immediate.
      io.immediate := Cat(Fill(xlen - 12, signBit), imm_11_5, imm_4_0).asUInt
    }

    is(ImmFormat.B) {
      val imm_b = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8)) // This forms imm[12:1]
      // Append LSB of 0, cast to SInt, then pad.
      io.immediate := Cat(imm_b, 0.U(1.W)).asSInt.pad(xlen).asUInt
    }

    is(ImmFormat.U) {
      val imm_31_12 = inst(31, 12)
      // The lower 12 bits are zero. No sign extension needed beyond the bits themselves.
      io.immediate := Cat(imm_31_12, 0.U(12.W)).asUInt
    }

    is(ImmFormat.J) {
      val imm_j = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21)) // This forms imm[20:1]
      // Append LSB of 0, cast to SInt, then pad.
      io.immediate := Cat(imm_j, 0.U(1.W)).asSInt.pad(xlen).asUInt
    }
  }
}
