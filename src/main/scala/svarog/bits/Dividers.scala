package svarog.bits

import chisel3._
import chisel3.util._

object DivOp extends ChiselEnum {
  val DIV, DIVU, REM, REMU = Value
}

class DividerIO(xlen: Int) extends Bundle {
  val op = DivOp.Type()
  val dividend = UInt(xlen.W)
  val divisor = UInt(xlen.W)
}

abstract class AbstractDivider(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val inp = Flipped(Decoupled(new DividerIO(xlen)))
    val result = Valid(UInt(xlen.W))
  })
}

class SimpleDivider(xlen: Int) extends AbstractDivider(xlen) {
  // Always ready to accept new inputs
  io.inp.ready := true.B

  // Default outputs
  io.result.valid := io.inp.valid
  io.result.bits := 0.U

  when(io.inp.valid) {
    val dividend = io.inp.bits.dividend
    val divisor = io.inp.bits.divisor

    // Perform division/remainder based on operation type
    switch(io.inp.bits.op) {
      is(DivOp.DIV) {
        // DIV: Signed division
        // Special cases per RISC-V spec:
        // - Division by zero: result = -1
        // - Overflow (most negative / -1): result = most negative
        when(divisor === 0.U) {
          io.result.bits := (-1).S(xlen.W).asUInt
        }.elsewhen(
          dividend === (1.U << (xlen - 1)) && divisor.asSInt === (-1).S
        ) {
          // Overflow case: -2^(xlen-1) / -1 = -2^(xlen-1)
          io.result.bits := dividend
        }.otherwise {
          val quotient = dividend.asSInt / divisor.asSInt
          io.result.bits := quotient.asUInt
        }
      }
      is(DivOp.DIVU) {
        // DIVU: Unsigned division
        // Special case: Division by zero returns all 1s (2^xlen - 1)
        when(divisor === 0.U) {
          io.result.bits := ((1.U << xlen) - 1.U)(xlen - 1, 0)
        }.otherwise {
          val quotient = dividend / divisor
          io.result.bits := quotient
        }
      }
      is(DivOp.REM) {
        // REM: Signed remainder
        // Special cases per RISC-V spec:
        // - Remainder by zero: result = dividend
        // - Overflow (most negative % -1): result = 0
        when(divisor === 0.U) {
          io.result.bits := dividend
        }.elsewhen(
          dividend === (1.U << (xlen - 1)) && divisor.asSInt === (-1).S
        ) {
          // Overflow case: -2^(xlen-1) % -1 = 0
          io.result.bits := 0.U
        }.otherwise {
          val remainder = dividend.asSInt % divisor.asSInt
          io.result.bits := remainder.asUInt
        }
      }
      is(DivOp.REMU) {
        // REMU: Unsigned remainder
        // Special case: Remainder by zero returns dividend
        when(divisor === 0.U) {
          io.result.bits := dividend
        }.otherwise {
          val remainder = dividend % divisor
          io.result.bits := remainder
        }
      }
    }
  }
}
