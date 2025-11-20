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

  // Disabled: This test doesn't properly emulate memory backpressure
  ignore should "not skip instructions when downstream stalls" in {
    println("\n=== Testing Fetch with downstream backpressure ===")

    val program = Seq(
      BigInt("00500093", 16), // addi x1, x0, 5
      BigInt("00308113", 16), // addi x2, x1, 3
      BigInt("00710193", 16), // addi x3, x2, 7
      BigInt("00000013", 16)  // nop
    )

    simulate(new Fetch(xlen, resetVector = 0)) { dut =>
      dut.io.branch.valid.poke(false.B)
      dut.io.debugSetPC.valid.poke(false.B)
      dut.io.halt.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      var fetchedInstructions = Seq[(Int, BigInt)]() // (PC, instruction)
      var cycle = 0

      // Helper to respond to memory request
      def respondToMemRequest(): Unit = {
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.address.peek().litValue.toInt
          val instrIdx = addr / 4
          if (instrIdx < program.length) {
            val bytes = wordToBytes(program(instrIdx), xlen / 8)
            for (i <- bytes.indices) {
              dut.io.mem.resp.bits.dataRead(i).poke(bytes(i).U(8.W))
            }
            dut.io.mem.resp.valid.poke(true.B)
            println(f"  [Cycle $cycle] Memory response: addr=0x$addr%08x, inst=0x${program(instrIdx)}%08x")
          }
        }
      }

      // Simulate with varying downstream readiness
      for (i <- 0 until 30) {
        cycle = i

        // Downstream ready pattern: ready for cycles 0-2, not ready 3-7, ready 8+
        val downstreamReady = if (i >= 3 && i <= 7) false else true
        dut.io.inst_out.ready.poke(downstreamReady.B)

        // Check if instruction is being output
        if (dut.io.inst_out.valid.peek().litToBoolean && downstreamReady) {
          val pc = dut.io.inst_out.bits.pc.peek().litValue.toInt
          val inst = dut.io.inst_out.bits.word.peek().litValue
          fetchedInstructions = fetchedInstructions :+ (pc, inst)
          println(f"  [Cycle $cycle] Fetched: PC=0x$pc%08x, inst=0x$inst%08x, ready=$downstreamReady")
        }

        respondToMemRequest()
        dut.clock.step(1)
        dut.io.mem.resp.valid.poke(false.B)
      }

      println(s"\n=== Fetched ${fetchedInstructions.length} instructions ===")
      for ((pc, inst) <- fetchedInstructions) {
        println(f"  PC=0x$pc%08x, inst=0x$inst%08x")
      }

      // Verify all 4 instructions were fetched
      fetchedInstructions.length should be (4)

      // Verify correct PCs
      fetchedInstructions(0)._1 should be (0x00)
      fetchedInstructions(1)._1 should be (0x04)
      fetchedInstructions(2)._1 should be (0x08)
      fetchedInstructions(3)._1 should be (0x0c)

      // Verify correct instructions
      fetchedInstructions(0)._2 should be (program(0))
      fetchedInstructions(1)._2 should be (program(1))
      fetchedInstructions(2)._2 should be (program(2))
      fetchedInstructions(3)._2 should be (program(3))
    }
  }
}
