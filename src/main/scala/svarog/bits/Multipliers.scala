package svarog.bits

import chisel3._
import chisel3.util._
import svarog.decoder.MicroOp
import svarog.decoder.OpType

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

class SimpleMultiplier(xlen: Int, val latency: Int = 3)
    extends AbstractMultiplier(xlen) {
  require(latency >= 1, "Latency must be at least 1")

  private val busy = RegInit(false.B)
  private val (counterValue, counterWrap) = Counter(busy, latency)

  io.inp.ready := !busy

  private val multiplicant =
    RegEnable(io.inp.bits.multiplicant, 0.U, io.inp.fire)
  private val multiplier = RegEnable(io.inp.bits.multiplier, 0.U, io.inp.fire)
  private val op = RegEnable(io.inp.bits.op, MulOp.MUL, io.inp.fire)

  private val result = WireDefault(0.U(xlen.W))
  switch(op) {
    is(MulOp.MUL) {
      val fullMul = multiplicant * multiplier
      result := fullMul(xlen - 1, 0)
    }
    is(MulOp.MULH) {
      val signedMul = multiplicant.asSInt * multiplier.asSInt
      result := signedMul.asUInt(2 * xlen - 1, xlen)
    }
    is(MulOp.MULHSU) {
      val mixedMul = multiplicant.asSInt * multiplier.zext
      result := mixedMul.asUInt(2 * xlen - 1, xlen)
    }
    is(MulOp.MULHU) {
      val fullMul = multiplicant * multiplier
      result := fullMul(2 * xlen - 1, xlen)
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
