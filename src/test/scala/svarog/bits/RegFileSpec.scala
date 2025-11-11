package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.matchers.must.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class RegFileSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "RegFile"

  private def disableExtraPort(dut: RegFile): Unit = {
    dut.extraWriteEn.poke(false.B)
    dut.extraWriteAddr.poke(0.U)
    dut.extraWriteData.poke(0.U)
  }

  def testPort(dut: RegFile, addr: Int, data: BigInt) = {
    disableExtraPort(dut)
    dut.writeIo.writeEn.poke(true.B)
    dut.writeIo.writeAddr.poke(addr.U)
    dut.writeIo.writeData.poke(data.U)
    dut.clock.step(1)

    dut.writeIo.writeEn.poke(false.B)

    dut.readIo.readAddr1.poke(addr.U)
    dut.readIo.readAddr2.poke(addr.U)

    dut.readIo.readData1.expect(data.U)
    dut.readIo.readData2.expect(data.U)
  }

  it should "write and read a value" in {
    simulate(new RegFile(32)) { dut =>
      disableExtraPort(dut)
      val testAddr = 5
      val testData = 12345
      testPort(dut, testAddr, testData)
    }
  }

  it should "not write to register x0" in {
    simulate(new RegFile(32)) { dut =>
      disableExtraPort(dut)
      dut.writeIo.writeEn.poke(true.B)
      dut.writeIo.writeAddr.poke(0.U)
      dut.writeIo.writeData.poke(99.U)
      dut.clock.step(1)

      dut.readIo.readAddr1.poke(0.U)
      dut.readIo.readData1.expect(0.U)
    }
  }

  it should "read the last written value" in {
    simulate(new RegFile(32)) { dut =>
      disableExtraPort(dut)
      val testAddr = 10

      dut.writeIo.writeEn.poke(true.B)
      dut.writeIo.writeAddr.poke(testAddr.U)
      dut.writeIo.writeData.poke(100.U)
      dut.clock.step(1)

      dut.writeIo.writeData.poke(200.U)
      dut.clock.step(1)

      dut.writeIo.writeEn.poke(false.B)

      dut.readIo.readAddr1.poke(testAddr.U)
      dut.readIo.readData1.expect(200.U)
    }
  }
}
