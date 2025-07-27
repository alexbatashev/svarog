package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class RegFileSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RegFile"

  def testPort(dut: RegFile, addr: Int, data: BigInt) = {
    dut.io.writeEn.poke(true.B)
    dut.io.writeAddr.poke(addr.U)
    dut.io.writeData.poke(data.U)
    dut.clock.step(1)

    dut.io.writeEn.poke(false.B)

    dut.io.readAddr1.poke(addr.U)
    dut.io.readAddr2.poke(addr.U)

    dut.io.readData1.expect(data.U)
    dut.io.readData2.expect(data.U)
  }

  it should "write and read a value" in {
    simulate(new RegFile(32)) { dut =>
      val testAddr = 5
      val testData = 12345
      testPort(dut, testAddr, testData)
    }
  }

  it should "not write to register x0" in {
    simulate(new RegFile(32)) { dut =>
      dut.io.writeEn.poke(true.B)
      dut.io.writeAddr.poke(0.U)
      dut.io.writeData.poke(99.U)
      dut.clock.step(1)

      dut.io.readAddr1.poke(0.U)
      dut.io.readData1.expect(0.U)
    }
  }

  it should "read the last written value" in {
    simulate(new RegFile(32)) { dut =>
      val testAddr = 10

      dut.io.writeEn.poke(true.B)
      dut.io.writeAddr.poke(testAddr.U)
      dut.io.writeData.poke(100.U)
      dut.clock.step(1)

      dut.io.writeData.poke(200.U)
      dut.clock.step(1)

      dut.io.writeEn.poke(false.B)

      dut.io.readAddr1.poke(testAddr.U)
      dut.io.readData1.expect(200.U)
    }
  }
}
