package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import svarog.memory.MemWidth

class FetchSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Fetch"

  private val xlen = 32

  private def wordToBytes(word: BigInt, numBytes: Int): Seq[Int] =
    (0 until numBytes).map(i => ((word >> (8 * i)) & 0xff).toInt)

  it should "issue sequential fetches and output pc/instruction pairs" in {
    val program = Seq(
      BigInt("11223344", 16),
      BigInt("88776655", 16),
      BigInt("cafebabe", 16),
      BigInt("01020304", 16)
    )

    simulate(new Fetch(xlen, resetVector = 0)) { dut =>
      dut.io.branch.valid.poke(false.B)
      dut.io.branch.bits.targetPC.poke(0.U)
      dut.io.inst_out.ready.poke(true.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.valid.poke(false.B)

      for ((instruction, idx) <- program.zipWithIndex) {
        val expectedPC = idx * 4

        dut.io.mem.req.valid.expect(true.B)
        dut.io.mem.req.bits.address.expect(expectedPC.U)
        dut.io.mem.req.bits.write.expect(false.B)

        // Wait one cycle before responding, emulating synchronous memory
        dut.clock.step(1)

        val bytes = wordToBytes(instruction, xlen / 8)
        for (i <- bytes.indices) {
          dut.io.mem.resp.bits.dataRead(i).poke(bytes(i).U(8.W))
        }
        dut.io.mem.resp.bits.valid.poke(true.B)
        dut.io.mem.resp.valid.poke(true.B)

        dut.io.inst_out.valid.expect(true.B)
        dut.io.inst_out.bits.word.expect(instruction.U)
        dut.io.inst_out.bits.pc.expect(expectedPC.U)

        dut.clock.step(1)

        dut.io.mem.resp.bits.valid.poke(false.B)
        dut.io.mem.resp.valid.poke(false.B)
      }
    }
  }
}
