package svarog.bits

import chisel3._
import chisel3.util._

object ALUOp extends ChiselEnum {
  val ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND = Value
}

class ALU(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val op = Input(ALUOp())
    val input1 = Input(UInt(xlen.W))
    val input2 = Input(UInt(xlen.W))

    val output = Output(UInt(xlen.W))
  })

  io.output := DontCare

  switch (io.op) {
    is(ALUOp.ADD) { io.output := io.input1 + io.input2 }
    is(ALUOp.SUB) { io.output := io.input1 - io.input2 }
    is(ALUOp.SLL) { io.output := io.input1 << io.input2(4, 0) }
    is(ALUOp.SRL) { io.output := io.input1 >> io.input2(4, 0) }
    is(ALUOp.SRA) { io.output := (io.input1.asSInt >> io.input2(4, 0)).asUInt }
    is(ALUOp.OR)  { io.output := io.input1 | io.input2 }
    is(ALUOp.AND) { io.output := io.input1 & io.input2 }
    is(ALUOp.XOR) { io.output := io.input1 ^ io.input2 }
    is(ALUOp.SLT) { io.output := (io.input1.asSInt < io.input2.asSInt).asUInt }
    is(ALUOp.SLTU) { io.output := (io.input1 < io.input2) }
  }
}
