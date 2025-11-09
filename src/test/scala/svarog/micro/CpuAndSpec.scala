package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class CpuAndSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU AND instruction"

  it should "execute AND correctly" in {
    simulate(new CpuTestHarness(32)) { dut =>
      // ADDI x1, x0, 5
      // ADDI x2, x0, 3
      // AND x3, x1, x2   -> should be 1 (5&3)

      val program = Seq(
        "h00500093".U,  // ADDI x1, x0, 5
        "h00300113".U,  // ADDI x2, x0, 3
        "h0020F1B3".U   // AND x3, x1, x2
      )

      var cycleCount = 0
      val maxCycles = 50
      var writes = Map[Int, Int]()

      println("\n=== AND Test ===")

      while (cycleCount < maxCycles) {
        val pc = dut.io.debug_pc.peek().litValue.toInt
        val instructionIdx = pc / 4

        if (instructionIdx < program.length) {
          dut.io.icache.respValid.poke(true.B)
          dut.io.icache.data.poke(program(instructionIdx))
        } else {
          dut.io.icache.respValid.poke(false.B)
          dut.io.icache.data.poke(0.U)
        }

        if (dut.io.debug_regWrite.peek().litToBoolean) {
          val addr = dut.io.debug_writeAddr.peek().litValue.toInt
          val data = dut.io.debug_writeData.peek().litValue.toInt
          writes = writes + (addr -> data)
          println(f"Cycle $cycleCount%3d: x$addr%2d <= $data%d (0x$data%08x)")
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(3)) {
          cycleCount = maxCycles
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes.contains(3), "x3 should have been written")
      assert(writes(1) == 5, s"x1 should be 5, got ${writes(1)}")
      assert(writes(2) == 3, s"x2 should be 3, got ${writes(2)}")
      assert(writes(3) == 1, s"x3 should be 1 (5&3), got ${writes(3)}")
      println("✓ AND test passed!\n")
    }
  }
}
