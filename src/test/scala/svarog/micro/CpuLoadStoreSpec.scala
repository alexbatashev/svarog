package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec
import svarog.memory.SimpleMemory

class CpuLoadStoreSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU Load/Store"

  it should "execute SW (store word) instruction" in {
    simulate(new Module {
      val cpu = Module(new CpuTestHarness(32))
      val dmem = Module(new SimpleMemory(32, 1024))  // 1KB data memory

      // Connect CPU to memory
      cpu.io.dcache <> dmem.io

      val io = IO(new Bundle {
        val icache = chiselTypeOf(cpu.io.icache)
        val debug_regWrite = Output(Bool())
        val debug_writeAddr = Output(UInt(5.W))
        val debug_writeData = Output(UInt(32.W))
        val debug_pc = Output(UInt(32.W))
      })

      io.icache <> cpu.io.icache
      io.debug_regWrite := cpu.io.debug_regWrite
      io.debug_writeAddr := cpu.io.debug_writeAddr
      io.debug_writeData := cpu.io.debug_writeData
      io.debug_pc := cpu.io.debug_pc
    }) { dut =>
      // Program:
      // ADDI x1, x0, 42   -> x1 = 42
      // SW x1, 0(x0)      -> mem[0] = 42
      val program = Seq(
        "h02A00093".U,  // ADDI x1, x0, 42
        "h00102023".U   // SW x1, 0(x0)  [offset=0, rs2=x1, rs1=x0]
      )

      var cycleCount = 0
      val maxCycles = 20
      var writes = Map[Int, Int]()
      var storeCompleted = false

      println("\n=== SW (Store Word) Test ===")
      println("Program:")
      println("  ADDI x1, x0, 42  # x1 = 42")
      println("  SW x1, 0(x0)     # mem[0] = 42")
      println()

      while (cycleCount < maxCycles && !storeCompleted) {
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

        // Check if we've moved past the SW instruction
        if (pc >= 8 && cycleCount > 10) {
          storeCompleted = true
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes(1) == 42, s"x1 should be 42, got ${writes(1)}")
      println("✓ Store test passed!")
      println()
    }
  }

  it should "execute LW (load word) instruction" in {
    simulate(new Module {
      val cpu = Module(new CpuTestHarness(32))
      val dmem = Module(new SimpleMemory(32, 1024))

      cpu.io.dcache <> dmem.io

      val io = IO(new Bundle {
        val icache = chiselTypeOf(cpu.io.icache)
        val debug_regWrite = Output(Bool())
        val debug_writeAddr = Output(UInt(5.W))
        val debug_writeData = Output(UInt(32.W))
        val debug_pc = Output(UInt(32.W))
      })

      io.icache <> cpu.io.icache
      io.debug_regWrite := cpu.io.debug_regWrite
      io.debug_writeAddr := cpu.io.debug_writeAddr
      io.debug_writeData := cpu.io.debug_writeData
      io.debug_pc := cpu.io.debug_pc
    }) { dut =>
      // Program:
      // ADDI x1, x0, 99   -> x1 = 99
      // SW x1, 0(x0)      -> mem[0] = 99
      // LW x2, 0(x0)      -> x2 = mem[0] = 99
      val program = Seq(
        "h06300093".U,  // ADDI x1, x0, 99
        "h00102023".U,  // SW x1, 0(x0)
        "h00002103".U   // LW x2, 0(x0)
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== LW (Load Word) Test ===")
      println("Program:")
      println("  ADDI x1, x0, 99  # x1 = 99")
      println("  SW x1, 0(x0)     # mem[0] = 99")
      println("  LW x2, 0(x0)     # x2 = mem[0]")
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
          cycleCount = maxCycles  // Exit
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes(1) == 99, s"x1 should be 99, got ${writes(1)}")
      assert(writes(2) == 99, s"x2 should be 99 (loaded from memory), got ${writes(2)}")
      println("✓ Load test passed!")
      println()
    }
  }

  it should "handle byte access (LB/SB)" in {
    simulate(new Module {
      val cpu = Module(new CpuTestHarness(32))
      val dmem = Module(new SimpleMemory(32, 1024))

      cpu.io.dcache <> dmem.io

      val io = IO(new Bundle {
        val icache = chiselTypeOf(cpu.io.icache)
        val debug_regWrite = Output(Bool())
        val debug_writeAddr = Output(UInt(5.W))
        val debug_writeData = Output(UInt(32.W))
        val debug_pc = Output(UInt(32.W))
      })

      io.icache <> cpu.io.icache
      io.debug_regWrite := cpu.io.debug_regWrite
      io.debug_writeAddr := cpu.io.debug_writeAddr
      io.debug_writeData := cpu.io.debug_writeData
      io.debug_pc := cpu.io.debug_pc
    }) { dut =>
      // Program:
      // ADDI x1, x0, 0x42 -> x1 = 0x42
      // SB x1, 0(x0)      -> mem[0] = 0x42 (byte)
      // LB x2, 0(x0)      -> x2 = 0x42 (sign-extended)
      val program = Seq(
        "h04200093".U,  // ADDI x1, x0, 0x42
        "h00100023".U,  // SB x1, 0(x0)
        "h00000103".U   // LB x2, 0(x0)
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== LB/SB (Byte Access) Test ===")

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

        if (writes.contains(2)) {
          cycleCount = maxCycles
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes(1) == 0x42, s"x1 should be 0x42, got 0x${writes(1).toHexString}")
      assert(writes(2) == 0x42, s"x2 should be 0x42, got 0x${writes(2).toHexString}")
      println("✓ Byte access test passed!")
      println()
    }
  }
}
