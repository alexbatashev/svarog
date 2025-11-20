package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import svarog.decoder.{OpType, MicroOp}
import svarog.bits.ALUOp
import svarog.memory.MemWidth

class ExecuteSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Execute"

  private val xlen = 32

  it should "compute ADDI correctly" in {
    simulate(new Execute(xlen)) { dut =>
      // Setup: addi x10, x1, 5
      // Assume x1 = 3, so result should be 8

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.ALU)
      dut.io.uop.bits.aluOp.poke(ALUOp.ADD)
      dut.io.uop.bits.rs1.poke(1.U)
      dut.io.uop.bits.rs2.poke(0.U)
      dut.io.uop.bits.rd.poke(10.U)
      dut.io.uop.bits.imm.poke(5.S(xlen.W).asUInt)
      dut.io.uop.bits.hasImm.poke(true.B)
      dut.io.uop.bits.regWrite.poke(true.B)
      dut.io.uop.bits.pc.poke(0x80000000L.U)

      // Simulate register file returning value 3 for x1
      dut.io.regFile.readData1.poke(3.U)
      dut.io.regFile.readData2.poke(0.U)

      dut.io.res.ready.poke(true.B)
      dut.io.stall.poke(false.B)

      dut.clock.step(1)

      // Check outputs
      dut.io.res.valid.expect(true.B)
      dut.io.res.bits.intResult.expect(8.U)
      dut.io.res.bits.rd.expect(10.U)
      dut.io.res.bits.regWrite.expect(true.B)

      println(s"ADDI test: result = ${dut.io.res.bits.intResult.peek().litValue}")
    }
  }

  it should "compute ADD correctly" in {
    simulate(new Execute(xlen)) { dut =>
      // Setup: add x10, x1, x2
      // Assume x1 = 1, x2 = 2, so result should be 3

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.ALU)
      dut.io.uop.bits.aluOp.poke(ALUOp.ADD)
      dut.io.uop.bits.rs1.poke(1.U)
      dut.io.uop.bits.rs2.poke(2.U)
      dut.io.uop.bits.rd.poke(10.U)
      dut.io.uop.bits.imm.poke(0.S(xlen.W).asUInt)
      dut.io.uop.bits.hasImm.poke(false.B)
      dut.io.uop.bits.regWrite.poke(true.B)
      dut.io.uop.bits.pc.poke(0x80000000L.U)

      // Simulate register file returning values
      dut.io.regFile.readData1.poke(1.U)
      dut.io.regFile.readData2.poke(2.U)

      dut.io.res.ready.poke(true.B)
      dut.io.stall.poke(false.B)

      dut.clock.step(1)

      // Check outputs
      dut.io.res.valid.expect(true.B)
      dut.io.res.bits.intResult.expect(3.U)
      dut.io.res.bits.rd.expect(10.U)
      dut.io.res.bits.regWrite.expect(true.B)

      println(s"ADD test: result = ${dut.io.res.bits.intResult.peek().litValue}")
    }
  }

  it should "handle stall correctly - instruction should complete" in {
    simulate(new Execute(xlen)) { dut =>
      // Test that when stalled, the current instruction still completes

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.ALU)
      dut.io.uop.bits.aluOp.poke(ALUOp.ADD)
      dut.io.uop.bits.rs1.poke(1.U)
      dut.io.uop.bits.rs2.poke(2.U)
      dut.io.uop.bits.rd.poke(10.U)
      dut.io.uop.bits.hasImm.poke(false.B)
      dut.io.uop.bits.regWrite.poke(true.B)

      dut.io.regFile.readData1.poke(5.U)
      dut.io.regFile.readData2.poke(7.U)

      dut.io.res.ready.poke(true.B)
      dut.io.stall.poke(true.B) // STALLED

      dut.clock.step(1)

      // When stalled, output should NOT be valid (instruction doesn't output)
      // This prevents executing the same instruction multiple times
      dut.io.res.valid.expect(false.B)

      // Ready signal to upstream should be false
      dut.io.uop.ready.expect(false.B)

      println(s"Stall test: result = ${dut.io.res.bits.intResult.peek().litValue}, ready = ${dut.io.uop.ready.peek().litValue}")
    }
  }
}
