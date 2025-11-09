package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec
import svarog.memory.SimpleMemory

class CpuBranchJumpSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU Branch and Jump Instructions"

  it should "execute BEQ (branch if equal) - taken" in {
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
      // ADDI x1, x0, 5    -> x1 = 5
      // ADDI x2, x0, 5    -> x2 = 5
      // BEQ x1, x2, 12    -> if x1 == x2, jump to PC+12 (skip 2 instructions)
      // ADDI x3, x0, 1    -> SKIPPED (x3 should stay 0)
      // ADDI x4, x0, 2    -> SKIPPED (x4 should stay 0)
      // ADDI x5, x0, 51   -> EXECUTED (x5 = 51)
      val program = Seq(
        "h00500093".U,  // 0x00: ADDI x1, x0, 5
        "h00500113".U,  // 0x04: ADDI x2, x0, 5
        "h00208663".U,  // 0x08: BEQ x1, x2, 12  (target = 0x08+12 = 0x14)
        "h00100193".U,  // 0x0C: ADDI x3, x0, 1  (should be skipped)
        "h00200213".U,  // 0x10: ADDI x4, x0, 2  (should be skipped)
        "h03300293".U   // 0x14: ADDI x5, x0, 51 (branch target)
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== BEQ (Branch if Equal - Taken) Test ===")
      println("Program:")
      println("  ADDI x1, x0, 5")
      println("  ADDI x2, x0, 5")
      println("  BEQ x1, x2, 12     # Should skip next 2 instructions")
      println("  ADDI x3, x0, 1     # SKIPPED")
      println("  ADDI x4, x0, 2     # SKIPPED")
      println("  ADDI x5, x0, 51    # EXECUTED")
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
            println(f"Cycle $cycleCount%3d: x$addr%2d <= $data%d")
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(5)) {
          cycleCount = maxCycles
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes.contains(5), "x5 should have been written")
      assert(writes(1) == 5, s"x1 should be 5, got ${writes(1)}")
      assert(writes(2) == 5, s"x2 should be 5, got ${writes(2)}")
      assert(!writes.contains(3), s"x3 should NOT have been written (skipped by branch)")
      assert(!writes.contains(4), s"x4 should NOT have been written (skipped by branch)")
      assert(writes(5) == 51, s"x5 should be 51, got ${writes(5)}")
      println("✓ BEQ taken test passed!")
      println()
    }
  }

  it should "execute BEQ (branch if equal) - not taken" in {
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
      // ADDI x1, x0, 5    -> x1 = 5
      // ADDI x2, x0, 7    -> x2 = 7
      // BEQ x1, x2, 8     -> if x1 == x2, jump (but they're not equal)
      // ADDI x3, x0, 10   -> EXECUTED (branch not taken)
      val program = Seq(
        "h00500093".U,  // ADDI x1, x0, 5
        "h00700113".U,  // ADDI x2, x0, 7
        "h00208463".U,  // BEQ x1, x2, 8
        "h00A00193".U   // ADDI x3, x0, 10
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== BEQ (Branch if Equal - Not Taken) Test ===")

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
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(3)) {
          cycleCount = maxCycles
        }
      }

      println(s"=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(writes.contains(3), "x3 should have been written (branch not taken)")
      assert(writes(1) == 5, s"x1 should be 5, got ${writes(1)}")
      assert(writes(2) == 7, s"x2 should be 7, got ${writes(2)}")
      assert(writes(3) == 10, s"x3 should be 10, got ${writes(3)}")
      println("✓ BEQ not taken test passed!")
      println()
    }
  }

  it should "execute BNE (branch if not equal)" in {
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
      // ADDI x1, x0, 5    -> x1 = 5
      // ADDI x2, x0, 7    -> x2 = 7
      // BNE x1, x2, 8     -> if x1 != x2, jump to PC+8 (skip 1 instruction)
      // ADDI x3, x0, 1    -> SKIPPED
      // ADDI x4, x0, 20   -> EXECUTED
      val program = Seq(
        "h00500093".U,  // 0x00: ADDI x1, x0, 5
        "h00700113".U,  // 0x04: ADDI x2, x0, 7
        "h00209463".U,  // 0x08: BNE x1, x2, 8  (target = 0x08+8 = 0x10)
        "h00100193".U,  // 0x0C: ADDI x3, x0, 1  (skipped)
        "h01400213".U   // 0x10: ADDI x4, x0, 20
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== BNE (Branch if Not Equal) Test ===")

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
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(4)) {
          cycleCount = maxCycles
        }
      }

      println(s"=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(!writes.contains(3), "x3 should NOT have been written (skipped)")
      assert(writes.contains(4), "x4 should have been written")
      assert(writes(4) == 20, s"x4 should be 20, got ${writes(4)}")
      println("✓ BNE test passed!")
      println()
    }
  }

  it should "execute BLT (branch if less than - signed)" in {
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
      // ADDI x1, x0, -5   -> x1 = -5 (0xFFFFFFFB)
      // ADDI x2, x0, 3    -> x2 = 3
      // BLT x1, x2, 8     -> if x1 < x2 (signed), jump to PC+8 (true: -5 < 3)
      // ADDI x3, x0, 1    -> SKIPPED
      // ADDI x4, x0, 42   -> EXECUTED
      val program = Seq(
        "hFFB00093".U,  // 0x00: ADDI x1, x0, -5
        "h00300113".U,  // 0x04: ADDI x2, x0, 3
        "h0020C463".U,  // 0x08: BLT x1, x2, 8  (target = 0x08+8 = 0x10)
        "h00100193".U,  // 0x0C: ADDI x3, x0, 1  (skipped)
        "h02A00213".U   // 0x10: ADDI x4, x0, 42
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== BLT (Branch if Less Than - Signed) Test ===")

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
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(4)) {
          cycleCount = maxCycles
        }
      }

      println(s"=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(!writes.contains(3), "x3 should NOT have been written (skipped)")
      assert(writes.contains(4), "x4 should have been written")
      assert(writes(4) == 42, s"x4 should be 42, got ${writes(4)}")
      println("✓ BLT test passed!")
      println()
    }
  }

  it should "execute BGE (branch if greater or equal - signed)" in {
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
      // ADDI x1, x0, 10   -> x1 = 10
      // ADDI x2, x0, 5    -> x2 = 5
      // BGE x1, x2, 8     -> if x1 >= x2 (signed), jump to PC+8 (true: 10 >= 5)
      // ADDI x3, x0, 1    -> SKIPPED
      // ADDI x4, x0, 99   -> EXECUTED
      val program = Seq(
        "h00A00093".U,  // 0x00: ADDI x1, x0, 10
        "h00500113".U,  // 0x04: ADDI x2, x0, 5
        "h0020D463".U,  // 0x08: BGE x1, x2, 8  (target = 0x08+8 = 0x10)
        "h00100193".U,  // 0x0C: ADDI x3, x0, 1  (skipped)
        "h06300213".U   // 0x10: ADDI x4, x0, 99
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== BGE (Branch if Greater or Equal - Signed) Test ===")

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
          }
        }

        dut.clock.step()
        cycleCount += 1

        if (writes.contains(4)) {
          cycleCount = maxCycles
        }
      }

      println(s"=== Results ===")
      assert(writes.contains(1), "x1 should have been written")
      assert(writes.contains(2), "x2 should have been written")
      assert(!writes.contains(3), "x3 should NOT have been written (skipped)")
      assert(writes.contains(4), "x4 should have been written")
      assert(writes(4) == 99, s"x4 should be 99, got ${writes(4)}")
      println("✓ BGE test passed!")
      println()
    }
  }

  it should "execute JAL (jump and link)" in {
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
      // JAL x1, 12        -> Jump to PC+12, save PC+4 in x1
      // ADDI x2, x0, 1    -> SKIPPED
      // ADDI x3, x0, 2    -> SKIPPED
      // ADDI x4, x0, 3    -> EXECUTED (jump target at PC=12)
      val program = Seq(
        "h00C000EF".U,  // 0x00: JAL x1, 12  (target = 0x00+12 = 0x0C)
        "h00100113".U,  // 0x04: ADDI x2, x0, 1  (skipped)
        "h00200193".U,  // 0x08: ADDI x3, x0, 2  (skipped)
        "h00300213".U   // 0x0C: ADDI x4, x0, 3  (jump target)
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== JAL (Jump and Link) Test ===")
      println("Program:")
      println("  JAL x1, 8         # Jump forward, save return address")
      println("  ADDI x2, x0, 1    # SKIPPED")
      println("  ADDI x3, x0, 2    # SKIPPED")
      println("  ADDI x4, x0, 3    # EXECUTED")
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

        if (writes.contains(4)) {
          cycleCount = maxCycles
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written (return address)")
      assert(writes.contains(4), "x4 should have been written")
      assert(writes(1) == 4, s"x1 should be 4 (PC+4), got ${writes(1)}")
      assert(!writes.contains(2), "x2 should NOT have been written (skipped)")
      assert(!writes.contains(3), "x3 should NOT have been written (skipped)")
      assert(writes(4) == 3, s"x4 should be 3, got ${writes(4)}")
      println("✓ JAL test passed!")
      println()
    }
  }

  it should "execute JALR (jump and link register)" in {
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
      // ADDI x5, x0, 16   -> x5 = 16 (target address)
      // JALR x1, x5, 0    -> Jump to x5+0, save PC+4 in x1
      // ADDI x2, x0, 1    -> SKIPPED
      // ADDI x3, x0, 2    -> SKIPPED
      // ADDI x4, x0, 99   -> EXECUTED at PC=16
      val program = Seq(
        "h01000293".U,  // 0x00: ADDI x5, x0, 16
        "h000280E7".U,  // 0x04: JALR x1, x5, 0
        "h00100113".U,  // 0x08: ADDI x2, x0, 1  (skipped)
        "h00200193".U,  // 0x0C: ADDI x3, x0, 2  (skipped)
        "h06300213".U   // 0x10: ADDI x4, x0, 99
      )

      var cycleCount = 0
      val maxCycles = 30
      var writes = Map[Int, Int]()

      println("\n=== JALR (Jump and Link Register) Test ===")
      println("Program:")
      println("  ADDI x5, x0, 16   # x5 = 16")
      println("  JALR x1, x5, 0    # Jump to x5, save return address")
      println("  ADDI x2, x0, 1    # SKIPPED")
      println("  ADDI x3, x0, 2    # SKIPPED")
      println("  ADDI x4, x0, 99   # EXECUTED")
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

        if (writes.contains(4)) {
          cycleCount = maxCycles
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(5), "x5 should have been written")
      assert(writes.contains(1), "x1 should have been written (return address)")
      assert(writes.contains(4), "x4 should have been written")
      assert(writes(5) == 16, s"x5 should be 16, got ${writes(5)}")
      assert(writes(1) == 8, s"x1 should be 8 (PC+4), got ${writes(1)}")
      assert(!writes.contains(2), "x2 should NOT have been written (skipped)")
      assert(!writes.contains(3), "x3 should NOT have been written (skipped)")
      assert(writes(4) == 99, s"x4 should be 99, got ${writes(4)}")
      println("✓ JALR test passed!")
      println()
    }
  }

  it should "handle function call and return pattern" in {
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
      // JAL x1, 16        -> Call function at PC+16 (jump to 0x10)
      // ADDI x2, x0, 1    -> After return
      // ADDI x6, x0, 100  -> After return
      // JAL x0, 12        -> Exit (infinite loop at end)
      // ADDI x5, x0, 42   -> Function body: x5 = 42
      // JALR x0, x1, 0    -> Return (jump to x1, don't save return addr)
      val program = Seq(
        "h010000EF".U,  // 0x00: JAL x1, 16  (target = 0x00+16 = 0x10)
        "h00100113".U,  // 0x04: ADDI x2, x0, 1
        "h06400313".U,  // 0x08: ADDI x6, x0, 100
        "h00C0006F".U,  // 0x0C: JAL x0, 12  (infinite loop/exit)
        "h02A00293".U,  // 0x10: ADDI x5, x0, 42  (function body)
        "h00008067".U   // 0x14: JALR x0, x1, 0   (return)
      )

      var cycleCount = 0
      val maxCycles = 50
      var writes = Map[Int, Int]()

      println("\n=== Function Call/Return Pattern Test ===")
      println("Program:")
      println("  JAL x1, 12        # Call function")
      println("  ADDI x2, x0, 1    # After return")
      println("  ADDI x6, x0, 100  # After return")
      println("  JAL x0, 12        # Exit")
      println("  ADDI x5, x0, 42   # Function: x5 = 42")
      println("  JALR x0, x1, 0    # Return")
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

        if (writes.contains(6)) {
          cycleCount = maxCycles
        }
      }

      println(s"\n=== Results ===")
      assert(writes.contains(1), "x1 should have been written (return address)")
      assert(writes.contains(5), "x5 should have been written (function executed)")
      assert(writes.contains(2), "x2 should have been written (after return)")
      assert(writes.contains(6), "x6 should have been written (after return)")
      assert(writes(1) == 4, s"x1 should be 4 (return address), got ${writes(1)}")
      assert(writes(5) == 42, s"x5 should be 42, got ${writes(5)}")
      assert(writes(2) == 1, s"x2 should be 1, got ${writes(2)}")
      assert(writes(6) == 100, s"x6 should be 100, got ${writes(6)}")
      println("✓ Function call/return test passed!")
      println()
    }
  }
}
