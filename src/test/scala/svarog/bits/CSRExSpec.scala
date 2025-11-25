package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import svarog.decoder.{OpType, MicroOp}

class CSRExSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "CSREx"

  private val xlen = 32

  it should "execute CSRRW (read-write) with register" in {
    simulate(new CSREx(xlen)) { dut =>
      // Setup: CSRRW x1, mvendorid, x2
      // Assume mvendorid (0xf11) = 0, x2 = 0x12345678
      // Result: x1 should get old CSR value (0), CSR should be written with 0x12345678

      // Create a valid uop for CSRRW
      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.CSRRW)
      dut.io.uop.bits.rs1.poke(2.U)
      dut.io.uop.bits.rd.poke(1.U)
      dut.io.uop.bits.csrAddr.poke(0xf11.U)
      dut.io.uop.bits.hasImm.poke(false.B)

      // Simulate CSR file returning 0 for mvendorid
      dut.io.csr.read.data.poke(0.U)

      // Pass rs1 value from Execute stage
      dut.io.rs1Value.poke(0x12345678L.U)

      dut.clock.step(1)

      // Check result - should return old CSR value
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(0.U)

      // Check CSR write signals
      dut.io.csr.write.en.expect(true.B)
      dut.io.csr.write.addr.expect(0xf11.U)
      dut.io.csr.write.data.expect(0x12345678L.U)

      println(s"CSRRW: result=${dut.io.result.bits.peek().litValue}, write=${dut.io.csr.write.data.peek().litValue}")
    }
  }

  it should "execute CSRRWI (read-write-immediate)" in {
    simulate(new CSREx(xlen)) { dut =>
      // Setup: CSRRWI x1, mvendorid, 5
      // Result: x1 should get old CSR value (0), CSR should be written with 5

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.CSRRW)
      dut.io.uop.bits.rs1.poke(0.U) // Not used for immediate form
      dut.io.uop.bits.rd.poke(1.U)
      dut.io.uop.bits.csrAddr.poke(0xf11.U)
      dut.io.uop.bits.hasImm.poke(true.B)
      dut.io.uop.bits.imm.poke(5.U)

      dut.io.csr.read.data.poke(0.U)

      dut.clock.step(1)

      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(0.U)
      dut.io.csr.write.en.expect(true.B)
      dut.io.csr.write.data.expect(5.U)

      println(s"CSRRWI: result=${dut.io.result.bits.peek().litValue}, write=${dut.io.csr.write.data.peek().litValue}")
    }
  }

  it should "execute CSRRS (read-set) with register" in {
    simulate(new CSREx(xlen)) { dut =>
      // Setup: CSRRS x1, misa, x2
      // Assume misa = 0x40000100 (RV32I), x2 = 0x00001000 (set bit 12)
      // Result: x1 = 0x40000100, CSR = 0x40001100

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.CSRRS)
      dut.io.uop.bits.rs1.poke(2.U)
      dut.io.uop.bits.rd.poke(1.U)
      dut.io.uop.bits.csrAddr.poke(0x301.U)
      dut.io.uop.bits.hasImm.poke(false.B)

      dut.io.csr.read.data.poke(0x40000100L.U)
      dut.io.rs1Value.poke(0x00001000L.U)

      dut.clock.step(1)

      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(0x40000100L.U)
      dut.io.csr.write.en.expect(true.B)
      dut.io.csr.write.data.expect(0x40001100L.U)

      println(f"CSRRS: result=0x${dut.io.result.bits.peek().litValue}%x, write=0x${dut.io.csr.write.data.peek().litValue}%x")
    }
  }

  it should "not write CSR for CSRRS when rs1=x0" in {
    simulate(new CSREx(xlen)) { dut =>
      // Setup: CSRRS x1, misa, x0 (read-only, no write)
      // Result: x1 = CSR value, no write

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.CSRRS)
      dut.io.uop.bits.rs1.poke(0.U)
      dut.io.uop.bits.rd.poke(1.U)
      dut.io.uop.bits.csrAddr.poke(0x301.U)
      dut.io.uop.bits.hasImm.poke(false.B)

      dut.io.csr.read.data.poke(0x40000100L.U)
      dut.io.rs1Value.poke(0.U)

      dut.clock.step(1)

      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(0x40000100L.U)
      dut.io.csr.write.en.expect(false.B) // No write when rs1=0

      println(f"CSRRS (x0): result=0x${dut.io.result.bits.peek().litValue}%x, write_en=${dut.io.csr.write.en.peek().litToBoolean}")
    }
  }

  it should "execute CSRRC (read-clear) with register" in {
    simulate(new CSREx(xlen)) { dut =>
      // Setup: CSRRC x1, misa, x2
      // Assume misa = 0x40001100, x2 = 0x00001000 (clear bit 12)
      // Result: x1 = 0x40001100, CSR = 0x40000100

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.CSRRC)
      dut.io.uop.bits.rs1.poke(2.U)
      dut.io.uop.bits.rd.poke(1.U)
      dut.io.uop.bits.csrAddr.poke(0x301.U)
      dut.io.uop.bits.hasImm.poke(false.B)

      dut.io.csr.read.data.poke(0x40001100L.U)
      dut.io.rs1Value.poke(0x00001000L.U)

      dut.clock.step(1)

      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(0x40001100L.U)
      dut.io.csr.write.en.expect(true.B)
      dut.io.csr.write.data.expect(0x40000100L.U)

      println(f"CSRRC: result=0x${dut.io.result.bits.peek().litValue}%x, write=0x${dut.io.csr.write.data.peek().litValue}%x")
    }
  }

  it should "execute CSRRCI (read-clear-immediate)" in {
    simulate(new CSREx(xlen)) { dut =>
      // Setup: CSRRCI x1, misa, 0x10 (clear bits in mask 0x10)
      // Assume misa = 0x40001110
      // Result: x1 = 0x40001110, CSR = 0x40001100

      dut.io.uop.valid.poke(true.B)
      dut.io.uop.bits.opType.poke(OpType.CSRRC)
      dut.io.uop.bits.rd.poke(1.U)
      dut.io.uop.bits.csrAddr.poke(0x301.U)
      dut.io.uop.bits.hasImm.poke(true.B)
      dut.io.uop.bits.imm.poke(0x10.U)

      dut.io.csr.read.data.poke(0x40001110L.U)

      dut.clock.step(1)

      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.expect(0x40001110L.U)
      dut.io.csr.write.en.expect(true.B)
      dut.io.csr.write.data.expect(0x40001100L.U)

      println(f"CSRRCI: result=0x${dut.io.result.bits.peek().litValue}%x, write=0x${dut.io.csr.write.data.peek().litValue}%x")
    }
  }

  it should "not output when uop is invalid" in {
    simulate(new CSREx(xlen)) { dut =>
      dut.io.uop.valid.poke(false.B)
      dut.io.csr.read.data.poke(0x12345678L.U)

      dut.clock.step(1)

      dut.io.result.valid.expect(false.B)
      dut.io.csr.write.en.expect(false.B)

      println("Invalid uop: no output")
    }
  }
}
