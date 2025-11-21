package svarog.bits

import chisel3._
import chisel3.util._

object MulOp extends ChiselEnum {
  val MUL, MULH, MULHSU, MULHU = Value
}

class MultiplierIO(xlen: Int) extends Bundle {
  val op = MulOp.Type()
  val multiplier = UInt(xlen.W)
  val multiplicant = UInt(xlen.W)
}

abstract class AbstractMultiplier(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val inp = Flipped(Decoupled(new MultiplierIO(xlen)))
    val result = Valid(UInt(xlen.W))
  })
}

class SimpleMultiplier(xlen: Int) extends AbstractMultiplier(xlen) {
  // Always ready to accept new inputs
  io.inp.ready := true.B

  // Default outputs
  io.result.valid := io.inp.valid
  io.result.bits := 0.U

  when(io.inp.valid) {
    val multiplier = io.inp.bits.multiplier
    val multiplicant = io.inp.bits.multiplicant

    // Perform multiplication based on operation type
    switch(io.inp.bits.op) {
      is(MulOp.MUL) {
        // MUL: Lower xlen bits of multiplicant * multiplier (both unsigned)
        val fullMul = multiplicant * multiplier
        io.result.bits := fullMul(xlen - 1, 0)
      }
      is(MulOp.MULH) {
        // MULH: Upper xlen bits of signed(multiplicant) * signed(multiplier)
        val signedMul = multiplicant.asSInt * multiplier.asSInt
        io.result.bits := signedMul.asUInt(2 * xlen - 1, xlen)
      }
      is(MulOp.MULHSU) {
        // MULHSU: Upper xlen bits of signed(multiplicant) * unsigned(multiplier)
        val mixedMul = multiplicant.asSInt * multiplier.zext
        io.result.bits := mixedMul.asUInt(2 * xlen - 1, xlen)
      }
      is(MulOp.MULHU) {
        // MULHU: Upper xlen bits of unsigned(multiplicant) * unsigned(multiplier)
        val fullMul = multiplicant * multiplier
        io.result.bits := fullMul(2 * xlen - 1, xlen)
      }
    }
  }
}
