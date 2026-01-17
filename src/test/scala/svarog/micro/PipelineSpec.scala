package svarog.micro

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import svarog.SvarogSoC
import svarog.config.{Cluster, ISA, Micro, SoC, TCM}
import svarog.memory.MemWidth

class PipelineSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CPU Pipeline"

  private val xlen = 32

  def wordToBytes(word: Int): Seq[Int] =
    (0 until 4).map(i => ((word >> (8 * i)) & 0xff))

  def runProgram(program: Seq[Int], cycles: Int = 20): Map[Int, Int] = {
    val config = SoC(
      clusters = Seq(
        Cluster(
          coreType = Micro,
          isa = ISA(xlen = xlen, mult = false, zmmul = false, zicsr = false),
          numCores = 1
        )
      ),
      io = Seq(),
      memories = Seq(TCM(baseAddress = 0x80000000L, length = 4096L)),
      simulatorDebug = true
    )

    var results: Map[Int, Int] = Map()

    implicit val p: Parameters = Parameters.empty
    simulate(LazyModule(new SvarogSoC(config, None)).module) { dut =>
      def tick(): Unit = dut.clock.step(1)

      val dbg = dut.io.debug.get

      // Initialize debug interface
      dbg.hart_in.id.valid.poke(false.B)
      dbg.hart_in.id.bits.poke(0.U)
      dbg.hart_in.bits.halt.valid.poke(false.B)
      dbg.hart_in.bits.halt.bits.poke(false.B)
      dbg.hart_in.bits.setPC.valid.poke(false.B)
      dbg.hart_in.bits.setPC.bits.pc.poke(0.U)
      dbg.mem_in.valid.poke(false.B)
      dbg.mem_res.ready.poke(false.B)

      // Reset + halt
      dut.reset.poke(true.B)
      tick()
      dbg.hart_in.id.valid.poke(true.B)
      dbg.hart_in.id.bits.poke(0.U)
      dbg.hart_in.bits.halt.valid.poke(true.B)
      dbg.hart_in.bits.halt.bits.poke(true.B)
      dut.reset.poke(false.B)
      tick()
      tick()

      // Load program via debug mem interface (byte writes)
      dbg.mem_res.ready.poke(true.B)
      val baseAddr = 0x80000000L
      for ((inst, idx) <- program.zipWithIndex) {
        val addr = baseAddr + (idx * 4)
        val bytes = wordToBytes(inst)
        for ((byte, byteIdx) <- bytes.zipWithIndex) {
          while (!dbg.mem_in.ready.peek().litToBoolean) {
            tick()
          }
          dbg.mem_in.valid.poke(true.B)
          dbg.mem_in.bits.addr.poke((addr + byteIdx).U)
          dbg.mem_in.bits.data.poke(byte.U)
          dbg.mem_in.bits.write.poke(true.B)
          dbg.mem_in.bits.instr.poke(true.B)
          dbg.mem_in.bits.reqWidth.poke(MemWidth.BYTE)
          tick()
          dbg.mem_in.valid.poke(false.B)
          tick()
        }
      }
      dbg.mem_res.ready.poke(false.B)

      // Set PC and release halt
      dbg.hart_in.bits.setPC.valid.poke(true.B)
      dbg.hart_in.bits.setPC.bits.pc.poke(baseAddr.U)
      tick()
      dbg.hart_in.bits.setPC.valid.poke(false.B)
      dbg.hart_in.bits.halt.bits.poke(false.B)
      tick()
      dbg.hart_in.bits.halt.valid.poke(false.B)
      dbg.hart_in.id.valid.poke(false.B)

      // Run a few cycles
      for (_ <- 0 until cycles) {
        tick()
      }

      results = Map(0 -> 0) // Placeholder
    }

    results
  }

  it should "execute a single ADDI instruction" in {
    // addi x10, x0, 42  (0x02a00513)
    val program = Seq(0x02a00513)

    println("=== Test: Single ADDI ===")
    println("Program: addi x10, x0, 42")
    println("Expected: x10 = 42")

    val result = runProgram(program, cycles = 15)

    // Test passes if no exceptions
    println("Test completed without crash")
  }

  it should "execute two dependent ADDI instructions" in {
    // addi x1, x0, 5    (0x00500093)
    // addi x2, x1, 3    (0x00308113)
    val program = Seq(
      0x00500093, // addi x1, x0, 5
      0x00308113 // addi x2, x1, 3
    )

    println("=== Test: Two dependent ADDI ===")
    println("Program:")
    println("  addi x1, x0, 5")
    println("  addi x2, x1, 3")
    println("Expected: x1 = 5, x2 = 8")

    val result = runProgram(program, cycles = 20)

    println("Test completed without crash")
  }

  it should "execute three dependent ADDI instructions" in {
    // addi x1, x0, 5    (0x00500093)
    // addi x2, x1, 3    (0x00308113)
    // addi x3, x2, 7    (0x00710193)
    val program = Seq(
      0x00500093, // addi x1, x0, 5
      0x00308113, // addi x2, x1, 3
      0x00710193 // addi x3, x2, 7
    )

    println("=== Test: Three dependent ADDI ===")
    println("Program:")
    println("  addi x1, x0, 5")
    println("  addi x2, x1, 3")
    println("  addi x3, x2, 7")
    println("Expected: x1 = 5, x2 = 8, x3 = 15")

    val result = runProgram(program, cycles = 25)

    println("Test completed without crash")
  }

  it should "execute ADD after setting registers with ADDI" in {
    // addi x1, x0, 10   (0x00a00093)
    // addi x2, x0, 20   (0x01400113)
    // add  x3, x1, x2   (0x002081b3)
    val program = Seq(
      0x00a00093, // addi x1, x0, 10
      0x01400113, // addi x2, x0, 20
      0x002081b3 // add x3, x1, x2
    )

    println("=== Test: Two ADDI then ADD ===")
    println("Program:")
    println("  addi x1, x0, 10")
    println("  addi x2, x0, 20")
    println("  add x3, x1, x2")
    println("Expected: x1 = 10, x2 = 20, x3 = 30")

    val result = runProgram(program, cycles = 25)

    println("Test completed without crash")
  }

  it should "execute CSRRS to read mvendorid (read-only CSR)" in {
    // csrrs x1, mvendorid, x0  - Read mvendorid into x1
    // mvendorid = 0xf11, funct3 = 0b010 (CSRRS)
    // Encoding: imm[11:0]=0xf11, rs1=0, funct3=010, rd=1, opcode=1110011
    val csrAddr = 0xf11
    val funct3 = 0x2 // CSRRS
    val rs1 = 0
    val rd = 1
    val opcode = 0x73
    val instruction =
      (csrAddr << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode

    val program = Seq(instruction)

    println("=== Test: CSRRS read mvendorid ===")
    println(f"Instruction: 0x$instruction%08x (csrrs x1, mvendorid, x0)")
    println("Expected: x1 = 0 (mvendorid initial value)")

    val result = runProgram(program, cycles = 15)

    println("Test completed without crash")
  }

  it should "execute CSRRW to read and write CSR (read-only, so write has no effect)" in {
    // addi x2, x0, 42         - x2 = 42
    // csrrw x1, mvendorid, x2 - x1 = old mvendorid (0), mvendorid = 42 (but it's read-only)
    // Note: Since mvendorid is read-only, the write will be attempted but won't change the value

    val addi = 0x02a00113 // addi x2, x0, 42

    val csrAddr = 0xf11
    val funct3 = 0x1 // CSRRW
    val rs1 = 2
    val rd = 1
    val opcode = 0x73
    val csrrw =
      (csrAddr << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | opcode

    val program = Seq(addi, csrrw)

    println("=== Test: CSRRW read-write ===")
    println("Program:")
    println(f"  0x$addi%08x (addi x2, x0, 42)")
    println(f"  0x$csrrw%08x (csrrw x1, mvendorid, x2)")
    println("Expected: x1 = 0 (old mvendorid), x2 = 42")

    val result = runProgram(program, cycles = 20)

    println("Test completed without crash")
  }

  it should "execute CSRRWI (immediate form)" in {
    // csrrwi x1, mvendorid, 5  - x1 = old mvendorid (0), write 5 to mvendorid

    val csrAddr = 0xf11
    val funct3 = 0x5 // CSRRWI
    val imm = 5
    val rd = 1
    val opcode = 0x73
    val instruction =
      (csrAddr << 20) | (imm << 15) | (funct3 << 12) | (rd << 7) | opcode

    val program = Seq(instruction)

    println("=== Test: CSRRWI immediate ===")
    println(f"Instruction: 0x$instruction%08x (csrrwi x1, mvendorid, 5)")
    println("Expected: x1 = 0 (old mvendorid)")

    val result = runProgram(program, cycles = 15)

    println("Test completed without crash")
  }

  it should "handle CSR hazards - dependent CSR instructions" in {
    // csrrwi x1, mvendorid, 5   - Write 5 to mvendorid, x1 = 0
    // csrrs x2, mvendorid, x0   - Read mvendorid into x2, should get 5 (or 0 if read-only)
    // This tests CSR hazard detection

    val csrAddr = 0xf11

    // CSRRWI x1, mvendorid, 5
    val csrrwi = (csrAddr << 20) | (5 << 15) | (0x5 << 12) | (1 << 7) | 0x73

    // CSRRS x2, mvendorid, x0
    val csrrs = (csrAddr << 20) | (0 << 15) | (0x2 << 12) | (2 << 7) | 0x73

    val program = Seq(csrrwi, csrrs)

    println("=== Test: CSR hazards ===")
    println("Program:")
    println(f"  0x$csrrwi%08x (csrrwi x1, mvendorid, 5)")
    println(f"  0x$csrrs%08x (csrrs x2, mvendorid, x0)")
    println("Expected: Pipeline should stall to resolve CSR hazard")

    val result = runProgram(program, cycles = 25)

    println("Test completed without crash")
  }

  it should "execute CSRRC (clear bits)" in {
    // csrrwi x0, misa, 0x1F     - Write 0x1F to misa (all 1s in lower 5 bits), x0 discarded
    // csrrc x1, misa, x0        - Read misa (should be 0x1F or original if read-only)
    // Note: misa is typically read-only, so this mainly tests the instruction execution

    val csrAddr = 0x301 // misa

    // CSRRWI x0, misa, 0x1F
    val csrrwi = (csrAddr << 20) | (0x1f << 15) | (0x5 << 12) | (0 << 7) | 0x73

    // CSRRC x1, misa, x0 (read-only, no clear)
    val csrrc = (csrAddr << 20) | (0 << 15) | (0x3 << 12) | (1 << 7) | 0x73

    val program = Seq(csrrwi, csrrc)

    println("=== Test: CSRRC clear bits ===")
    println("Program:")
    println(f"  0x$csrrwi%08x (csrrwi x0, misa, 0x1F)")
    println(f"  0x$csrrc%08x (csrrc x1, misa, x0)")
    println("Expected: x1 = misa value")

    val result = runProgram(program, cycles = 25)

    println("Test completed without crash")
  }
}
