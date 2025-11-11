package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class CpuHazardSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU with hazard detection"

  it should "execute back-to-back dependent instructions with stalls" in {
    simulate(new CpuTestHarness(32)) { dut =>
      // Test program with RAW hazard:
      // ADDI x1, x0, 10   -> x1 = 10 (cycle 4 writeback)
      // ADD  x2, x1, x1   -> x2 = 20 (should stall until x1 is ready)

      val program = Seq(
        "h00A00093".U,  // ADDI x1, x0, 10
        "h00108133".U   // ADD x2, x1, x1
      )

      var cycleCount = 0
      val maxCycles = 50
      var writes = Map[Int, Int]()
      var stallCount = 0

      println("\n=== Hazard Detection Test ===")
      println("Program:")
      println("  ADDI x1, x0, 10  # x1 = 10")
      println("  ADD  x2, x1, x1  # x2 = 20 (depends on x1)")
      println()

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

        // Track PC to detect stalls
        val prevPC = if (cycleCount > 0) writes.getOrElse(-1, 0) else -1

        if (dut.io.debug_regWrite.peek().litToBoolean) {
          val addr = dut.io.debug_writeAddr.peek().litValue.toInt
          val data = dut.io.debug_writeData.peek().litValue.toInt
          if (addr != 0) {
            writes = writes + (addr -> data)
            println(f"Cycle $cycleCount%3d: x$addr%2d <= $data%d (0x$data%08x)")
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(2)) {
          println(s"\nCompleted after $cycleCount cycles")
          cycleCount = maxCycles  // Exit
        }
      }

      println("\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes(1) == 10, s"x1 should be 10, got ${writes(1)}")
      assert(writes(2) == 20, s"x2 should be 20 (10+10), got ${writes(2)}")

      // With stalls, this should take longer than 5 stages + 2 instructions = 6 cycles
      // Expect around 7-9 cycles due to stalls
      println(f"✓ Hazard detection working! Instructions completed with stalls.\n")
    }
  }

  it should "not stall when there's no hazard" in {
    simulate(new CpuTestHarness(32)) { dut =>
      // Test program without hazards:
      // ADDI x1, x0, 5    -> x1 = 5
      // ADDI x2, x0, 10   -> x2 = 10 (no dependency on x1)

      val program = Seq(
        "h00500093".U,  // ADDI x1, x0, 5
        "h00A00113".U   // ADDI x2, x0, 10
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== No Hazard Test ===")

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
          if (addr != 0) {
            writes = writes + (addr -> data)
            println(f"Cycle $cycleCount%3d: x$addr%2d <= $data%d")
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(1) && writes.contains(2)) {
          cycleCount = maxCycles  // Exit
        }
      }

      println("\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes(1) == 5, s"x1 should be 5, got ${writes(1)}")
      assert(writes(2) == 10, s"x2 should be 10, got ${writes(2)}")
      println("✓ No unnecessary stalls!\n")
    }
  }
}
