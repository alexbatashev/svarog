package svarog.memory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TCMSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "TCM"

  private val xlen = 32
  private val memSizeBytes = 256
  private val wordBytes = xlen / 8

  private def bytesFromWord(value: BigInt): Seq[Int] =
    (0 until wordBytes).map { idx =>
      ((value >> (8 * idx)) & 0xff).toInt
    }

  private def writeWord(dut: TCM, address: BigInt, data: BigInt): Unit = {
    val port = dut.io.ports(0)
    port.req.bits.address.poke(address.U)
    port.req.bits.write.poke(true.B)
    port.req.bits.reqWidth.poke(MemWidth.WORD)
    bytesFromWord(data).zipWithIndex.foreach { case (byte, idx) =>
      port.req.bits.dataWrite(idx).poke(byte.U(8.W))
    }
    port.req.valid.poke(true.B)
    port.resp.ready.poke(true.B)

    dut.clock.step(1)

    port.resp.valid.expect(true.B)
    port.resp.bits.valid.expect(true.B)

    port.req.valid.poke(false.B)
    port.req.bits.write.poke(false.B)
  }

  private def readWordAndExpect(
      dut: TCM,
      address: BigInt,
      expected: BigInt
  ): Unit = {
    val port = dut.io.ports(0)
    port.req.bits.address.poke(address.U)
    port.req.bits.write.poke(false.B)
    port.req.bits.reqWidth.poke(MemWidth.WORD)
    bytesFromWord(0).zipWithIndex.foreach { case (byte, idx) =>
      port.req.bits.dataWrite(idx).poke(byte.U(8.W))
    }
    port.req.valid.poke(true.B)
    port.resp.ready.poke(true.B)

    dut.clock.step(1)
    dut.clock.step(1)

    port.resp.valid.expect(true.B)
    port.resp.bits.valid.expect(true.B)
    bytesFromWord(expected).zipWithIndex.foreach { case (byte, idx) =>
      port.resp.bits.dataRead(idx).expect(byte.U)
    }

    port.req.valid.poke(false.B)
  }

  it should "read and write at base address 0x0" in {
    simulate(new TCM(xlen, memSizeBytes, baseAddr = 0L, numPorts = 1)) { dut =>
      val testAddress = BigInt(0)
      val testData = BigInt("deadbeef", 16)

      writeWord(dut, testAddress, testData)
      readWordAndExpect(dut, testAddress, testData)
    }
  }

  it should "read and write when base is 0x80000000" in {
    val baseAddress = BigInt("80000000", 16)
    simulate(
      new TCM(xlen, memSizeBytes, baseAddr = baseAddress.toLong, numPorts = 1)
    ) { dut =>
      val testAddress = baseAddress
      val testData = BigInt("cafebabe", 16)

      writeWord(dut, testAddress, testData)
      readWordAndExpect(dut, testAddress, testData)
    }
  }
}
