package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class CpuSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU"

  it should "execute simple ALU instructions" in {
    simulate(new CpuTestHarness(32)) { dut =>

      // Simple test program:
      // 0x00: ADDI x1, x0, 5    -> x1 = 5
      // 0x04: ADDI x2, x0, 3    -> x2 = 3
      // 0x08: ADD  x3, x1, x2   -> x3 = 8
      // 0x0C: SUB  x4, x1, x2   -> x4 = 2
      // 0x10: AND  x5, x1, x2   -> x5 = 1

      val program = Seq(
        "h00500093".U,  // ADDI x1, x0, 5
        "h00300113".U,  // ADDI x2, x0, 3
        "h002081B3".U,  // ADD x3, x1, x2
        "h40208233".U,  // SUB x4, x1, x2
        "h0020F2B3".U   // AND x5, x1, x2
      )

      var instructionIndex = 0
      var cycleCount = 0
      val maxCycles = 100

      // Keep track of register writes we've seen
      var writes = Map[Int, Int]()

      println("\n=== CPU Test Started ===")

      while (cycleCount < maxCycles) {
        // Simulate instruction cache responses
        // Return instructions based on PC
        val pc = dut.io.debug_pc.peek().litValue.toInt
        val instructionIdx = pc / 4

        if (instructionIdx < program.length) {
          dut.io.icache.respValid.poke(true.B)
          dut.io.icache.data.poke(program(instructionIdx))
        } else {
          dut.io.icache.respValid.poke(false.B)
          dut.io.icache.data.poke(0.U)
        }

        // Check for register writes
        if (dut.io.debug_regWrite.peek().litToBoolean) {
          val addr = dut.io.debug_writeAddr.peek().litValue.toInt
          val data = dut.io.debug_writeData.peek().litValue.toInt
          writes = writes + (addr -> data)
          println(f"Cycle $cycleCount%3d: x$addr%2d <= 0x$data%08x ($data%d)")
        }

        dut.clock.step()
        cycleCount += 1

        // Stop when we've written to x5 (the last instruction)
        if (writes.contains(5)) {
          println(s"\nTest completed after $cycleCount cycles")
          cycleCount = maxCycles  // Exit loop
        }
      }

      println("\n=== Final Register Values ===")
      writes.toSeq.sortBy(_._1).foreach { case (reg, value) =>
        println(f"x$reg%2d = 0x$value%08x ($value%d)")
      }

      // Verify expected results
      println("\n=== Verification ===")

      // Due to pipeline latency, it takes 5 cycles for first instruction to commit
      // So we need to run enough cycles for all instructions to complete

      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes.contains(3), "x3 should have been written")
      assert(writes.contains(4), "x4 should have been written")
      assert(writes.contains(5), "x5 should have been written")

      assert(writes(1) == 5, s"x1 should be 5, got ${writes(1)}")
      assert(writes(2) == 3, s"x2 should be 3, got ${writes(2)}")
      assert(writes(3) == 8, s"x3 should be 8 (5+3), got ${writes(3)}")
      assert(writes(4) == 2, s"x4 should be 2 (5-3), got ${writes(4)}")
      assert(writes(5) == 1, s"x5 should be 1 (5&3), got ${writes(5)}")

      println("✓ All tests passed!")
      println("\n=== CPU Test Complete ===\n")
    }
  }

  it should "execute LUI instruction" in {
    simulate(new CpuTestHarness(32)) { dut =>

      // Test program:
      // 0x00: LUI x7, 0x12345    -> x7 = 0x12345000

      val program = Seq(
        "h123453B7".U   // LUI x7, 0x12345
      )

      var cycleCount = 0
      val maxCycles = 50
      var foundWrite = false

      println("\n=== LUI Test Started ===")

      while (cycleCount < maxCycles && !foundWrite) {
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

          println(f"Cycle $cycleCount%3d: x$addr%2d <= 0x$data%08x")

          if (addr == 7) {
            assert(data == 0x12345000, f"x7 should be 0x12345000, got 0x$data%08x")
            println("✓ LUI test passed!")
            foundWrite = true
          }
        }

        dut.clock.step()
        cycleCount += 1
      }

      assert(foundWrite, "LUI instruction should have written to x7")
      println("\n=== LUI Test Complete ===\n")
    }
  }

  it should "execute AUIPC instruction" in {
    simulate(new CpuTestHarness(32)) { dut =>

      // Test program:
      // 0x00: AUIPC x1, 0x1000   -> x1 = 0x00 + 0x1000000 = 0x1000000

      val program = Seq(
        "h01000097".U   // AUIPC x1, 0x1000
      )

      var cycleCount = 0
      val maxCycles = 50
      var foundWrite = false

      println("\n=== AUIPC Test Started ===")

      while (cycleCount < maxCycles && !foundWrite) {
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

          println(f"Cycle $cycleCount%3d: x$addr%2d <= 0x$data%08x")

          if (addr == 1) {
            // AUIPC should be PC (0x00) + (0x1000 << 12) = 0x1000000
            assert(data == 0x01000000, f"x1 should be 0x01000000 (PC + imm), got 0x$data%08x")
            println("✓ AUIPC test passed!")
            foundWrite = true
          }
        }

        dut.clock.step()
        cycleCount += 1
      }

      assert(foundWrite, "AUIPC instruction should have written to x1")
      println("\n=== AUIPC Test Complete ===\n")
    }
  }
}
