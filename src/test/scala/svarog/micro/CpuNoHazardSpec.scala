package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class CpuNoHazardSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU without hazards"

  it should "execute ADDI instructions" in {
    simulate(new Cpu(32)) { dut =>
      // Test program with NOPs to avoid hazards:
      // 0x00: ADDI x1, x0, 42   -> x1 = 42
      // 0x04: NOP (ADDI x0, x0, 0)
      // 0x08: NOP
      // 0x0C: NOP
      // 0x10: NOP
      // 0x14: ADDI x2, x0, 17   -> x2 = 17

      val program = Seq(
        "h02A00093".U, // ADDI x1, x0, 42
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h01100113".U // ADDI x2, x0, 17
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== Simple ADDI Test ===")

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
          if (addr != 0) { // Ignore writes to x0
            writes = writes + (addr -> data)
            println(f"Cycle $cycleCount%3d: x$addr%2d <= $data%d (0x$data%08x)")
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(1) && writes.contains(2)) {
          cycleCount = maxCycles // Exit
        }
      }

      println("\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes(1) == 42, s"x1 should be 42, got ${writes(1)}")
      assert(writes(2) == 17, s"x2 should be 17, got ${writes(2)}")
      println("✓ All ADDI tests passed!\n")
    }
  }

  it should "execute ADD with NOPs to avoid hazards" in {
    simulate(new Cpu(32)) { dut =>
      // Test program:
      // ADDI x1, x0, 10
      // NOP x 4 (wait for x1 to be written)
      // ADDI x2, x0, 20
      // NOP x 4 (wait for x2 to be written)
      // ADD x3, x1, x2  -> x3 = 30

      val program = Seq(
        "h00A00093".U, // ADDI x1, x0, 10
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h01400113".U, // ADDI x2, x0, 20
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h00000013".U, // NOP
        "h002081B3".U // ADD x3, x1, x2
      )

      var cycleCount = 0
      val maxCycles = 50
      var writes = Map[Int, Int]()

      println("\n=== ADD with NOPs Test ===")

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
            println(f"Cycle $cycleCount%3d: x$addr%2d <= $data%d (0x$data%08x)")
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(3)) {
          cycleCount = maxCycles // Exit
        }
      }

      println("\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes.contains(3), "x3 should have been written")
      assert(writes(1) == 10, s"x1 should be 10, got ${writes(1)}")
      assert(writes(2) == 20, s"x2 should be 20, got ${writes(2)}")
      assert(writes(3) == 30, s"x3 should be 30 (10+20), got ${writes(3)}")
      println("✓ ADD test passed!\n")
    }
  }

  it should "execute LUI instruction" in {
    simulate(new Cpu(32)) { dut =>
      // LUI x7, 0xABCDE -> x7 = 0xABCDE000

      val program = Seq(
        "hABCDE3B7".U // LUI x7, 0xABCDE
      )

      var cycleCount = 0
      val maxCycles = 20
      var foundWrite = false

      println("\n=== LUI Test ===")

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
            assert(
              data == 0xabcde000L.toInt,
              f"x7 should be 0xABCDE000, got 0x$data%08x"
            )
            println("✓ LUI test passed!")
            foundWrite = true
          }
        }

        dut.clock.step()
        cycleCount += 1
      }

      assert(foundWrite, "LUI instruction should have written to x7")
      println()
    }
  }
}
