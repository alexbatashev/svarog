package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FetchSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "Fetch"

  val xlen = 32

  def simulateICacheHit(dut: Fetch, addr: Int, instruction: Long): Unit = {
    // These are now INPUTS to the flipped interface
    dut.io.icache.respValid.poke(true.B)
    dut.io.icache.data.poke(instruction.U)
  }

  def simulateICacheMiss(dut: Fetch): Unit = {
    dut.io.icache.respValid.poke(false.B)
  }

  it should "increment PC by 4 on normal operation" in {
    simulate(new Fetch(xlen)) { dut =>
      // Initialize
      dut.io.stall.poke(false.B)
      dut.io.flush.poke(false.B)
      dut.io.branch_taken.poke(false.B)

      // Check initial PC
      dut.io.pc_out.expect(0.U)
      dut.io.icache.addr.expect(0.U)

      // Simulate cache hit with instruction
      simulateICacheHit(dut, 0x0, 0x12345678L)

      dut.clock.step(1)

      // PC should increment to 4
      dut.io.pc_out.expect(4.U)
      dut.io.icache.addr.expect(4.U)
      dut.io.instruction.expect(0x12345678.U)
      dut.io.valid.expect(true.B)

      // Another step
      simulateICacheHit(dut, 0x4, 0x87654321L)
      dut.clock.step(1)

      dut.io.pc_out.expect(8.U)
      dut.io.instruction.expect(0x87654321L.U)
    }
  }
}
