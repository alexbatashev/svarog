package svarog.bits

import chisel3._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.should.Matchers

class ImmGenSpec extends AnyFlatSpec with ChiselSim with Matchers {
  behavior of "ImmGen"

  val xlen = 32

  def testCase(dut: ImmGen, format: ImmFormat.Type, instruction: Long, expectedImm: BigInt): Unit = {
    dut.io.format.poke(format)
    dut.io.instruction.poke(instruction.U)
    // Compare the output to the expected value. The .asUInt converts the signed
    // BigInt into a UInt with the equivalent bit pattern for comparison.
    dut.io.immediate.expect(expectedImm.S(xlen.W).asUInt)
  }

  it should "generate I-type immediates correctly" in {
    simulate(new ImmGen(xlen)) { dut =>
      // Corresponds to: addi x5, x6, -1
      testCase(dut, ImmFormat.I, 0xfff30293L, -1)
      // Corresponds to: lw x5, 12(x6)
      testCase(dut, ImmFormat.I, 0x00c32283L, 12)
    }
  }

  it should "generate S-type immediates correctly" in {
    simulate(new ImmGen(xlen)) { dut =>
      // Corresponds to: sw x5, -4(x6)
      testCase(dut, ImmFormat.S, 0xfe532e23L, -4)
      // Corresponds to: sh x5, 20(x6)
      testCase(dut, ImmFormat.S, 0x01431a23L, 20)
    }
  }

  it should "generate B-type immediates correctly" in {
    simulate(new ImmGen(xlen)) { dut =>
      // Corresponds to: bne x5, x6, -8
      testCase(dut, ImmFormat.B, 0xfe629ce3L, -8)
      // Corresponds to: beq x5, x6, 16
      testCase(dut, ImmFormat.B, 0x00628863L, 16)
    }
  }

  it should "generate U-type immediates correctly" in {
    simulate(new ImmGen(xlen)) { dut =>
      // Corresponds to: lui x5, 0xABCDE
      testCase(dut, ImmFormat.U, 0xabcde2b7L, 0xabcde000)
      // Corresponds to: auipc x5, 0x12345
      testCase(dut, ImmFormat.U, 0x12345297L, 0x12345000)
    }
  }

  it should "generate J-type immediates correctly" in {
    simulate(new ImmGen(xlen)) { dut =>
      // Corresponds to: jal x5, -16
      testCase(dut, ImmFormat.J, 0xff1ff2efL, -16)
      // Corresponds to: jal x5, 20
      testCase(dut, ImmFormat.J, 0x014002efL, 20)
    }
  }
}
