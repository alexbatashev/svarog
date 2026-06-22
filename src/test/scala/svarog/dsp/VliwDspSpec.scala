package svarog.dsp

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VliwDspSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "VliwDsp"

  private val p = DspParams(xlen = 32, numLanes = 4, numRegs = 16)
  private val mask = (BigInt(1) << p.xlen) - 1

  /** Drive a host write into a DSP register and let it commit. */
  private def loadReg(dut: VliwDsp, addr: Int, value: BigInt): Unit = {
    dut.io.inst.valid.poke(false.B)
    dut.io.hostWriteEn.poke(true.B)
    dut.io.hostWriteAddr.poke(addr.U)
    dut.io.hostWriteData.poke((value & mask).U)
    dut.clock.step(1)
    dut.io.hostWriteEn.poke(false.B)
  }

  /** Issue one VLIW bundle word and let the lane writes commit. */
  private def issue(dut: VliwDsp, word: BigInt): Unit = {
    dut.io.hostWriteEn.poke(false.B)
    dut.io.inst.valid.poke(true.B)
    dut.io.inst.bits.poke(word.U(p.bundleBits.W))
    dut.clock.step(1)
    dut.io.inst.valid.poke(false.B)
  }

  /** Read a DSP register back through the host port (combinational). */
  private def readReg(dut: VliwDsp, addr: Int): BigInt = {
    dut.io.hostReadAddr.poke(addr.U)
    dut.io.hostReadData.peek().litValue
  }

  it should "always be ready to accept a bundle" in {
    simulate(new VliwDsp(p)) { dut =>
      dut.io.inst.ready.expect(true.B)
    }
  }

  it should "execute add, sub and mul lanes in parallel in one bundle" in {
    simulate(new VliwDsp(p)) { dut =>
      loadReg(dut, 1, 20)
      loadReg(dut, 2, 6)

      // r3 = r1 + r2; r4 = r1 - r2; r5 = r1 * r2; lane3 NOP
      val word = DspAsm.bundle(
        DspAsm.add(rd = 3, rs1 = 1, rs2 = 2),
        DspAsm.sub(rd = 4, rs1 = 1, rs2 = 2),
        DspAsm.mul(rd = 5, rs1 = 1, rs2 = 2)
      )
      issue(dut, word)

      readReg(dut, 3) shouldBe BigInt(26)
      readReg(dut, 4) shouldBe BigInt(14)
      readReg(dut, 5) shouldBe BigInt(120)
    }
  }

  it should "leave the destination untouched for NOP lanes" in {
    simulate(new VliwDsp(p)) { dut =>
      loadReg(dut, 1, 7)
      loadReg(dut, 2, 3)
      loadReg(dut, 6, 0xabcd)

      // A bundle of pure NOPs must change nothing.
      issue(dut, DspAsm.bundle(DspAsm.nop, DspAsm.nop, DspAsm.nop, DspAsm.nop))
      readReg(dut, 6) shouldBe BigInt(0xabcd)

      // One real op, the rest NOP: only r3 changes.
      issue(dut, DspAsm.bundle(DspAsm.add(rd = 3, rs1 = 1, rs2 = 2)))
      readReg(dut, 3) shouldBe BigInt(10)
      readReg(dut, 6) shouldBe BigInt(0xabcd)
    }
  }

  it should "let the highest lane win when two lanes write the same register" in {
    simulate(new VliwDsp(p)) { dut =>
      loadReg(dut, 1, 100)
      loadReg(dut, 2, 1)

      // lane0: r7 = r1 + r2 (=101), lane2: r7 = r1 - r2 (=99).
      // Lane 2 is higher, so it wins.
      val word = DspAsm.bundle(
        DspAsm.add(rd = 7, rs1 = 1, rs2 = 2),
        DspAsm.nop,
        DspAsm.sub(rd = 7, rs1 = 1, rs2 = 2)
      )
      issue(dut, word)
      readReg(dut, 7) shouldBe BigInt(99)
    }
  }

  it should "observe read-before-write semantics within a bundle" in {
    simulate(new VliwDsp(p)) { dut =>
      loadReg(dut, 1, 5)
      loadReg(dut, 2, 9)

      // Swap r1 and r2 in a single bundle. If writes were visible to reads
      // mid-bundle this would corrupt; with read-before-write it is a clean swap.
      val word = DspAsm.bundle(
        DspAsm.add(rd = 1, rs1 = 2, rs2 = 0), // r1 = r2 + r0(=0)
        DspAsm.add(rd = 2, rs1 = 1, rs2 = 0) // r2 = r1 + r0(=0)
      )
      issue(dut, word)
      readReg(dut, 1) shouldBe BigInt(9)
      readReg(dut, 2) shouldBe BigInt(5)
    }
  }

  it should "keep only the low xlen bits of a multiply" in {
    simulate(new VliwDsp(p)) { dut =>
      loadReg(dut, 1, BigInt("FFFFFFFF", 16)) // -1 / max
      loadReg(dut, 2, BigInt("FFFFFFFF", 16))

      issue(dut, DspAsm.bundle(DspAsm.mul(rd = 3, rs1 = 1, rs2 = 2)))
      // 0xFFFFFFFF * 0xFFFFFFFF = 0xFFFFFFFE00000001, low 32 bits = 1
      readReg(dut, 3) shouldBe BigInt(1)
    }
  }

  it should "run a small multi-bundle DSP program (2-tap FIR sample)" in {
    simulate(new VliwDsp(p)) { dut =>
      // Compute y = c0*x0 + c1*x1 using the four lanes plus a reduction bundle.
      val c0 = 3; val x0 = 7
      val c1 = 5; val x1 = 11
      loadReg(dut, 1, c0)
      loadReg(dut, 2, x0)
      loadReg(dut, 3, c1)
      loadReg(dut, 4, x1)

      // Bundle 1: r5 = c0*x0, r6 = c1*x1 (two products in parallel).
      issue(
        dut,
        DspAsm.bundle(
          DspAsm.mul(rd = 5, rs1 = 1, rs2 = 2),
          DspAsm.mul(rd = 6, rs1 = 3, rs2 = 4)
        )
      )
      // Bundle 2: r7 = r5 + r6 (reduction).
      issue(dut, DspAsm.bundle(DspAsm.add(rd = 7, rs1 = 5, rs2 = 6)))

      val expected = c0 * x0 + c1 * x1
      readReg(dut, 7) shouldBe BigInt(expected)
    }
  }
}
