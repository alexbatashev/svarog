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

class SimpleDivider(xlen: Int, val latency: Int = 320)
    extends AbstractDivider(xlen) {
  require(latency >= 1, "Latency must be at least 1")

  private val busy = RegInit(false.B)
  private val (counterValue, counterWrap) = Counter(busy, latency)

  io.inp.ready := !busy

  private val dividend =
    RegEnable(io.inp.bits.dividend, 0.U, io.inp.fire)
  private val divisor = RegEnable(io.inp.bits.divisor, 0.U, io.inp.fire)
  private val op = RegEnable(io.inp.bits.op, DivOp.DIV, io.inp.fire)

  private val result = WireDefault(0.U(xlen.W))
  // Perform division/remainder based on operation type
  switch(op) {
    is(DivOp.DIV) {
      // DIV: Signed division
      // Special cases per RISC-V spec:
      // - Division by zero: result = -1
      // - Overflow (most negative / -1): result = most negative
      when(divisor === 0.U) {
        result := (-1).S(xlen.W).asUInt
      }.elsewhen(
        dividend === (1.U << (xlen - 1)) && divisor.asSInt === (-1).S
      ) {
        // Overflow case: -2^(xlen-1) / -1 = -2^(xlen-1)
        result := dividend
      }.otherwise {
        val quotient = dividend.asSInt / divisor.asSInt
        result := quotient.asUInt
      }
    }
    is(DivOp.DIVU) {
      // DIVU: Unsigned division
      // Special case: Division by zero returns all 1s (2^xlen - 1)
      when(divisor === 0.U) {
        result := ((BigInt(1) << xlen) - 1).U(xlen.W)
      }.otherwise {
        val quotient = dividend / divisor
        result := quotient
      }
    }
    is(DivOp.REM) {
      // REM: Signed remainder
      // Special cases per RISC-V spec:
      // - Remainder by zero: result = dividend
      // - Overflow (most negative % -1): result = 0
      when(divisor === 0.U) {
        result := dividend
      }.elsewhen(
        dividend === (1.U << (xlen - 1)) && divisor.asSInt === (-1).S
      ) {
        // Overflow case: -2^(xlen-1) % -1 = 0
        result := 0.U
      }.otherwise {
        val remainder = dividend.asSInt % divisor.asSInt
        result := remainder.asUInt
      }
    }
    is(DivOp.REMU) {
      // REMU: Unsigned remainder
      // Special case: Remainder by zero returns dividend
      when(divisor === 0.U) {
        result := dividend
      }.otherwise {
        val remainder = dividend % divisor
        result := remainder
      }
    }
  }

  io.result.bits := ShiftRegister(result, latency - 1, 0.U, true.B)
  io.result.valid := counterValue === (latency - 1).U && busy

  when(io.inp.fire) {
    busy := true.B
  }
  when(counterWrap) {
    busy := false.B
  }
}
