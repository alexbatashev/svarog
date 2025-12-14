package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import svarog.soc.{SvarogSoC, SvarogConfig}
import svarog.memory.MemWidth

class CpuDebugSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU with Debug Interface"

  private val xlen = 32

  def wordToBytes(word: Int): Seq[Int] =
    (0 until 4).map(i => ((word >> (8 * i)) & 0xff))

  /**
   * Run a program using the debug interface (similar to Verilator testbench)
   * 1. Halt CPU
   * 2. Load program into memory via debug interface
   * 3. Release halt
   * 4. Wait for execution
   * 5. Halt CPU again
   * 6. Read registers via debug interface
   */
  def runProgramViaDebug(program: Seq[Int], cycles: Int = 50): Map[Int, Int] = {
    val config = SvarogConfig(
      xlen = xlen,
      memSizeBytes = 4096,
      programEntryPoint = 0x80000000L,
      enableDebugInterface = true
    )
    var results: Map[Int, Int] = Map()

    simulate(new SvarogSoC(config)) { dut =>
      // Helper to tick clock
      def tick(): Unit = {
        dut.clock.step(1)
      }

      // Initialize debug interface
      dut.io.debug.hart_in.id.valid.poke(false.B)
      dut.io.debug.hart_in.id.bits.poke(0.U)
      dut.io.debug.hart_in.bits.halt.valid.poke(false.B)
      dut.io.debug.hart_in.bits.halt.bits.poke(false.B)
      dut.io.debug.hart_in.bits.breakpoint.valid.poke(false.B)
      dut.io.debug.hart_in.bits.watchpoint.valid.poke(false.B)
      dut.io.debug.hart_in.bits.register.valid.poke(false.B)
      dut.io.debug.hart_in.bits.register.bits.reg.poke(0.U)
      dut.io.debug.hart_in.bits.register.bits.write.poke(false.B)
      dut.io.debug.hart_in.bits.register.bits.data.poke(0.U)
      dut.io.debug.hart_in.bits.setPC.valid.poke(false.B)
      dut.io.debug.hart_in.bits.setPC.bits.pc.poke(0.U)
      dut.io.debug.mem_in.valid.poke(false.B)
      dut.io.debug.reg_res.ready.poke(false.B)

      // Reset
      dut.reset.poke(true.B)
      tick()
      tick()

      // Halt immediately after reset to prevent fetching garbage
      // IMPORTANT: Assert halt BEFORE releasing reset so it takes effect on cycle 0
      println("=== Step 1: Halt CPU ===")
      dut.io.debug.hart_in.id.valid.poke(true.B)
      dut.io.debug.hart_in.id.bits.poke(0.U) // Hart 0
      dut.io.debug.hart_in.bits.halt.valid.poke(true.B)
      dut.io.debug.hart_in.bits.halt.bits.poke(true.B)

      dut.reset.poke(false.B)
      tick()

      // Give it a few more cycles for halt to propagate
      tick()
      tick()

      // Verify halt
      val halted = dut.io.debug.halted.peek().litToBoolean
      println(s"CPU halted: $halted")
      assert(halted, "CPU should be halted")

      println("=== Step 2: Load program into memory ===")
      dut.io.debug.mem_res.ready.poke(true.B) // Ready to consume write responses
      val baseAddr = 0x80000000L
      for ((inst, idx) <- program.zipWithIndex) {
        val addr = baseAddr + (idx * 4)
        val bytes = wordToBytes(inst)

        // Write each byte
        for ((byte, byteIdx) <- bytes.zipWithIndex) {
          // Wait for mem_in to be ready (memPending must be clear)
          while (!dut.io.debug.mem_in.ready.peek().litToBoolean) {
            tick()
          }

          dut.io.debug.mem_in.valid.poke(true.B)
          dut.io.debug.mem_in.bits.addr.poke((addr + byteIdx).U)
          dut.io.debug.mem_in.bits.data.poke(byte.U)
          dut.io.debug.mem_in.bits.write.poke(true.B)
          dut.io.debug.mem_in.bits.instr.poke(true.B) // Writing to instruction memory
          dut.io.debug.mem_in.bits.reqWidth.poke(MemWidth.BYTE) // Byte width
          tick()
          dut.io.debug.mem_in.valid.poke(false.B)

          // Wait for response to be consumed and memPending to clear
          tick()
          tick()
        }

        println(f"  Loaded instruction [$idx]: 0x$inst%08x at address 0x$addr%08x")
      }

      // Clear memory write
      dut.io.debug.mem_in.valid.poke(false.B)
      dut.io.debug.mem_res.ready.poke(false.B)
      tick()

      println("=== Step 2.5: Verify program was loaded ===\"")
      dut.io.debug.mem_res.ready.poke(true.B)
      for ((inst, idx) <- program.zipWithIndex) {
        val addr = baseAddr + (idx * 4)
        // Issue read request
        dut.io.debug.mem_in.valid.poke(true.B)
        dut.io.debug.mem_in.bits.addr.poke(addr.U)
        dut.io.debug.mem_in.bits.write.poke(false.B)
        dut.io.debug.mem_in.bits.instr.poke(true.B)
        dut.io.debug.mem_in.bits.reqWidth.poke(MemWidth.WORD)
        tick()

        // Clear request and wait for response
        dut.io.debug.mem_in.valid.poke(false.B)

        // Wait for response (should come on next cycle)
        var found = false
        for (attempt <- 0 until 10) {
          val valid = dut.io.debug.mem_res.valid.peek().litToBoolean
          if (valid) {
            val readBack = dut.io.debug.mem_res.bits.peek().litValue.toInt
            println(f"  Verify: addr=0x$addr%08x, expected=0x$inst%08x, got=0x$readBack%08x")
            found = true
          } else {
            if (attempt < 3) println(f"    Attempt $attempt: mem_res.valid=$valid")
          }
          tick()
        }
        if (!found) {
          println(f"  Verify: addr=0x$addr%08x - no response after 10 cycles!")
        }
      }
      dut.io.debug.mem_res.ready.poke(false.B)

      println("=== Step 2.7: Set PC and flush pipeline ===")
      dut.io.debug.hart_in.id.valid.poke(true.B)
      dut.io.debug.hart_in.id.bits.poke(0.U)
      dut.io.debug.hart_in.bits.setPC.valid.poke(true.B)
      dut.io.debug.hart_in.bits.setPC.bits.pc.poke(baseAddr.U)
      tick()
      dut.io.debug.hart_in.bits.setPC.valid.poke(false.B)
      tick()
      println(s"  PC set to 0x${baseAddr.toHexString}, pipeline flushed")

      println("=== Step 2.8: Set breakpoint at last instruction ===")
      val breakpointAddr = baseAddr + ((program.length - 1) * 4)
      dut.io.debug.hart_in.id.valid.poke(true.B)
      dut.io.debug.hart_in.id.bits.poke(0.U)
      dut.io.debug.hart_in.bits.breakpoint.valid.poke(true.B)
      dut.io.debug.hart_in.bits.breakpoint.bits.pc.poke(breakpointAddr.U)
      tick()
      dut.io.debug.hart_in.bits.breakpoint.valid.poke(false.B)
      tick()
      println(s"  Breakpoint set at 0x${breakpointAddr.toHexString} (at last instruction)")

      println("=== Step 3: Release halt to start execution ===")
      println(s"  Before: halt output = ${dut.io.debug.halted.peek().litToBoolean}")
      println(s"  Before: id.valid = ${dut.io.debug.hart_in.id.valid.peek().litToBoolean}, id.bits = ${dut.io.debug.hart_in.id.bits.peek().litValue}")
      println(s"  Before: halt.valid = ${dut.io.debug.hart_in.bits.halt.valid.peek().litToBoolean}, halt.bits = ${dut.io.debug.hart_in.bits.halt.bits.peek().litToBoolean}")

      println(s"  Poking: id.valid=true, halt.valid=true, halt.bits=false (release)")
      dut.io.debug.hart_in.id.valid.poke(true.B)
      dut.io.debug.hart_in.id.bits.poke(0.U)
      dut.io.debug.hart_in.bits.halt.valid.poke(true.B)
      dut.io.debug.hart_in.bits.halt.bits.poke(false.B) // Release
      tick()
      println(s"  After 1 tick: halt output = ${dut.io.debug.halted.peek().litToBoolean}")

      // IMPORTANT: Clear id.valid and halt.valid to enter "don't care" state
      // This allows internal events (watchpoints, breakpoints) to assert halt
      println(s"  Clearing id.valid and halt.valid to 'don't care' state")
      dut.io.debug.hart_in.id.valid.poke(false.B)
      dut.io.debug.hart_in.bits.halt.valid.poke(false.B)
      tick()
      println(s"  After clearing: halt output = ${dut.io.debug.halted.peek().litToBoolean}")

      // Verify halt released
      val running = !dut.io.debug.halted.peek().litToBoolean
      println(s"CPU running: $running")

      println("=== Step 4: Let program execute until breakpoint ===")
      val expectedInstructions = program.length
      val programEndAddr = baseAddr + (expectedInstructions * 4)

      // Run until breakpoint is hit (halted becomes true) or timeout
      var cycleCount = 0
      val maxCycles = cycles
      while (!dut.io.debug.halted.peek().litToBoolean && cycleCount < maxCycles) {
        tick()
        cycleCount += 1
      }

      val haltedByBreakpoint = dut.io.debug.halted.peek().litToBoolean
      println(s"  Ran for $cycleCount cycles (expected ${expectedInstructions} instructions)")
      println(s"  Program loaded at 0x${baseAddr.toHexString}, ends at 0x${programEndAddr.toHexString}")
      println(s"  Halted by breakpoint: $haltedByBreakpoint")

      if (!haltedByBreakpoint) {
        println(s"  WARNING: Breakpoint not hit after $maxCycles cycles, manually halting")
        println("=== Step 5: Halt CPU manually ===")
        dut.io.debug.hart_in.id.valid.poke(true.B)
        dut.io.debug.hart_in.id.bits.poke(0.U)
        dut.io.debug.hart_in.bits.halt.valid.poke(true.B)
        dut.io.debug.hart_in.bits.halt.bits.poke(true.B)
        tick()
        tick()
        tick()
      } else {
        println("=== Step 5: Already halted by breakpoint ===")
      }

      // Give a few more cycles for pipeline to drain after halting
      println("  Draining pipeline...")
      for (_ <- 0 until 10) {
        tick()
      }

      println("=== Step 6: Read registers via debug interface ===")
      dut.io.debug.reg_res.ready.poke(true.B)
      dut.io.debug.hart_in.id.valid.poke(true.B)
      dut.io.debug.hart_in.id.bits.poke(0.U)

      for (reg <- 0 until 32) {
        // Issue read request
        dut.io.debug.hart_in.bits.register.valid.poke(true.B)
        dut.io.debug.hart_in.bits.register.bits.reg.poke(reg.U)
        dut.io.debug.hart_in.bits.register.bits.write.poke(false.B)
        tick()

        // Clear request
        dut.io.debug.hart_in.bits.register.valid.poke(false.B)

        // Poll for result (with timeout)
        var value = 0
        var found = false
        for (attempt <- 0 until 10) {
          if (dut.io.debug.reg_res.valid.peek().litToBoolean) {
            value = dut.io.debug.reg_res.bits.peek().litValue.toInt
            found = true
            println(f"  x$reg%2d = 0x$value%08x ($value%d)")
            results = results + (reg -> value)
            // Don't break - let the test continue to drain the valid signal
          }
          if (found && attempt > 0) {
            // We found it on a previous iteration, just draining now
          }
          tick()
        }

        if (!found) {
          println(f"  x$reg%2d = TIMEOUT (assuming 0)")
          results = results + (reg -> 0)
        }
      }

      println("=== Test Complete ===")
    }

    results
  }

  it should "execute a single ADDI instruction via debug interface" in {
    println("\n" + "="*60)
    println("TEST: Single ADDI instruction")
    println("="*60)

    // addi x10, x0, 42  (0x02a00513)
    // nop (0x00000013) - for breakpoint
    val program = Seq(
      0x02a00513,
      0x00000013  // NOP for breakpoint
    )

    val regs = runProgramViaDebug(program, cycles = 200)

    println("\nExpected: x10 = 42")
    println(f"Got:      x10 = ${regs(10)}")

    regs(10) should be (42)
  }

  it should "execute two dependent ADDI instructions via debug interface" in {
    println("\n" + "="*60)
    println("TEST: Two dependent ADDI instructions")
    println("="*60)

    // addi x1, x0, 5    (0x00500093)
    // addi x2, x1, 3    (0x00308113)
    // nop (0x00000013) - for breakpoint
    val program = Seq(
      0x00500093,
      0x00308113,
      0x00000013  // NOP for breakpoint
    )

    val regs = runProgramViaDebug(program, cycles = 25)

    println("\nExpected: x1 = 5, x2 = 8")
    println(f"Got:      x1 = ${regs(1)}, x2 = ${regs(2)}")

    regs(1) should be (5)
    regs(2) should be (8)
  }

  it should "execute three dependent ADDI instructions via debug interface" in {
    println("\n" + "="*60)
    println("TEST: Three dependent ADDI instructions")
    println("="*60)

    // addi x1, x0, 5    (0x00500093)
    // addi x2, x1, 3    (0x00308113)
    // addi x3, x2, 7    (0x00710193)
    // nop for breakpoint
    val program = Seq(
      0x00500093,
      0x00308113,
      0x00710193,
      0x00000013   // NOP - breakpoint here
    )

    val regs = runProgramViaDebug(program, cycles = 100)

    println("\nExpected: x1 = 5, x2 = 8, x3 = 15")
    println(f"Got:      x1 = ${regs(1)}, x2 = ${regs(2)}, x3 = ${regs(3)}")

    regs(1) should be (5)
    regs(2) should be (8)
    regs(3) should be (15)
  }

  it should "execute ADD after setting registers with ADDI via debug interface" in {
    println("\n" + "="*60)
    println("TEST: Two ADDI then ADD")
    println("="*60)

    // addi x1, x0, 10   (0x00a00093)
    // addi x2, x0, 20   (0x01400113)
    // add  x3, x1, x2   (0x002081b3)
    // nop for breakpoint
    val program = Seq(
      0x00a00093,
      0x01400113,
      0x002081b3,
      0x00000013   // NOP - breakpoint here
    )

    val regs = runProgramViaDebug(program, cycles = 100)

    println("\nExpected: x1 = 10, x2 = 20, x3 = 30")
    println(f"Got:      x1 = ${regs(1)}, x2 = ${regs(2)}, x3 = ${regs(3)}")

    regs(1) should be (10)
    regs(2) should be (20)
    regs(3) should be (30)
  }
}
