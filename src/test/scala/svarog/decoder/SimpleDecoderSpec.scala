package svarog.decoder

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import chisel3.util.{Decoupled, Valid}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import svarog.bits.ALUOp
import svarog.memory.MemWidth

private class SimpleDecoderHarness(xlen: Int) extends Module {
  private val monitorSample = new MicroOp(xlen)
  val io = IO(new Bundle {
    val inst = Flipped(Decoupled(new InstWord(xlen)))
    val decoded = Decoupled(new MicroOp(xlen))
    val hazard = Valid(new SimpleDecodeHazardIO)
    val monitor = Output(new Bundle {
      val opType = UInt(monitorSample.opType.getWidth.W)
      val aluOp = UInt(monitorSample.aluOp.getWidth.W)
      val memWidth = UInt(monitorSample.memWidth.getWidth.W)
    })
  })

  private val dut = Module(new SimpleDecoder(xlen))
  dut.io.inst <> io.inst
  io.decoded <> dut.io.decoded
  io.hazard <> dut.io.hazard

  io.monitor.opType := dut.io.decoded.bits.opType.asUInt
  io.monitor.aluOp := dut.io.decoded.bits.aluOp.asUInt
  io.monitor.memWidth := dut.io.decoded.bits.memWidth.asUInt
}

class SimpleDecoderSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "SimpleDecoder"

  private val xlen = 32

  private case class DecodeVector(
      pc: Int,
      instruction: BigInt,
      check: SimpleDecoderHarness => Unit
  )

  it should "decode common instruction types and expose hazard info" in {
    val vectors = Seq(
      DecodeVector(
        pc = 0,
        instruction = BigInt("00500093", 16), // addi x1, x0, 5
        check = { dut =>
          dut.io.monitor.opType.expect(OpType.ALU.litValue.U)
          dut.io.decoded.bits.rd.expect(1.U)
          dut.io.decoded.bits.rs1.expect(0.U)
          dut.io.decoded.bits.hasImm.expect(true.B)
          dut.io.decoded.bits.imm.expect(5.U)
          dut.io.decoded.bits.regWrite.expect(true.B)
          dut.io.monitor.aluOp.expect(ALUOp.ADD.litValue.U)
          dut.io.hazard.bits.rs1.expect(0.U)
          dut.io.hazard.bits.rs2.expect(0.U)
        }
      ),
      DecodeVector(
        pc = 4,
        instruction = BigInt("002081B3", 16), // add x3, x1, x2
        check = { dut =>
          dut.io.monitor.opType.expect(OpType.ALU.litValue.U)
          dut.io.decoded.bits.rd.expect(3.U)
          dut.io.decoded.bits.rs1.expect(1.U)
          dut.io.decoded.bits.rs2.expect(2.U)
          dut.io.decoded.bits.hasImm.expect(false.B)
          dut.io.decoded.bits.regWrite.expect(true.B)
          dut.io.monitor.aluOp.expect(ALUOp.ADD.litValue.U)
          dut.io.hazard.bits.rs1.expect(1.U)
          dut.io.hazard.bits.rs2.expect(2.U)
        }
      ),
      DecodeVector(
        pc = 8,
        instruction = BigInt("0080A203", 16), // lw x4, 8(x1)
        check = { dut =>
          dut.io.monitor.opType.expect(OpType.LOAD.litValue.U)
          dut.io.decoded.bits.rd.expect(4.U)
          dut.io.decoded.bits.rs1.expect(1.U)
          dut.io.decoded.bits.hasImm.expect(true.B)
          dut.io.decoded.bits.imm.expect(8.U)
          dut.io.monitor.memWidth.expect(MemWidth.WORD.litValue.U)
          dut.io.decoded.bits.memUnsigned.expect(false.B)
          dut.io.decoded.bits.regWrite.expect(true.B)
          dut.io.hazard.bits.rs1.expect(1.U)
          dut.io.hazard.bits.rs2.expect(0.U)
        }
      )
    )

    simulate(new SimpleDecoderHarness(xlen)) { dut =>
      dut.io.decoded.ready.poke(true.B)
      dut.io.inst.valid.poke(false.B)
      dut.io.inst.bits.word.poke(0.U)
      dut.io.inst.bits.pc.poke(0.U)

      for (vector <- vectors) {
        dut.io.inst.bits.word.poke(vector.instruction.U)
        dut.io.inst.bits.pc.poke(vector.pc.U)
        dut.io.inst.valid.poke(true.B)

        dut.clock.step(1)

        dut.io.decoded.valid.expect(true.B)
        dut.io.inst.ready.expect(true.B)
        dut.io.decoded.bits.pc.expect(vector.pc.U)
        dut.io.hazard.valid.expect(true.B)

        vector.check(dut)

        dut.io.inst.valid.poke(false.B)
        dut.clock.step(1)
        dut.io.decoded.valid.expect(false.B)
        dut.io.hazard.valid.expect(false.B)
      }
    }
  }
}
