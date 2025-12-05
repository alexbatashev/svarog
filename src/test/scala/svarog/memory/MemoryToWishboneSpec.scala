package svarog.memory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemoryToWishboneSpec extends AnyFlatSpec with Matchers with ChiselSim {

  behavior of "MemoryToWishbone"

  it should "convert a read request to Wishbone" in {
    simulate(new MemoryToWishbone(xlen = 32)) { dut =>
      println("=== Test: Read request ===")

      // Initial state
      dut.memIO.req.valid.poke(false.B)
      dut.memIO.resp.ready.poke(true.B)
      dut.io.ack.poke(false.B)
      dut.io.stall.poke(false.B)
      dut.clock.step()

      // CPU makes read request
      dut.memIO.req.valid.poke(true.B)
      dut.memIO.req.bits.address.poke(0x80000004L.U)
      dut.memIO.req.bits.write.poke(false.B)
      dut.memIO.req.bits.reqWidth.poke(MemWidth.WORD)
      dut.memIO.req.bits.dataWrite.foreach(_.poke(0.U))

      // Should be ready initially
      println(s"memIO.req.ready: ${dut.memIO.req.ready.peek().litToBoolean}")
      dut.memIO.req.ready.expect(true.B, "Adapter should be ready for request")

      dut.clock.step()

      // After accepting request, should drive Wishbone signals
      println(s"Wishbone cyc: ${dut.io.cyc.peek().litToBoolean}")
      println(s"Wishbone stb: ${dut.io.stb.peek().litToBoolean}")
      println(s"Wishbone we: ${dut.io.we.peek().litToBoolean}")
      println(s"Wishbone addr: 0x${dut.io.addr.peek().litValue.toString(16)}")

      dut.io.cyc.expect(true.B, "CYC should be asserted")
      dut.io.stb.expect(true.B, "STB should be asserted")
      dut.io.we.expect(false.B, "WE should be low for read")
      dut.io.addr.expect(0x80000004L.U, "Address should match")

      // Wishbone slave responds
      dut.io.ack.poke(true.B)
      dut.io.stall.poke(false.B)
      dut.io.dataToMaster.poke(0xDEADBEEFL.U)
      dut.clock.step()

      // Response should be forwarded to CPU
      println(s"memIO.resp.valid: ${dut.memIO.resp.valid.peek().litToBoolean}")
      dut.memIO.resp.valid.expect(true.B, "Response should be valid")
      dut.memIO.resp.bits.valid.expect(true.B, "Response data should be valid")

      // Check data
      val readData = (0 until 4).map { i =>
        dut.memIO.resp.bits.dataRead(i).peek().litValue.toInt
      }
      val readWord = readData.zipWithIndex.map { case (byte, i) => byte << (8 * i) }.sum
      println(s"Read data: 0x${readWord.toHexString}")
      assert(readWord == 0xDEADBEEFL, s"Data mismatch: got 0x${readWord.toHexString}, expected 0xDEADBEEF")

      println("✓ Read request test passed")
    }
  }

  it should "handle Wishbone stall correctly" in {
    simulate(new MemoryToWishbone(xlen = 32)) { dut =>
      println("=== Test: Wishbone stall ===")

      // Initial state
      dut.memIO.req.valid.poke(false.B)
      dut.memIO.resp.ready.poke(true.B)
      dut.io.ack.poke(false.B)
      dut.io.stall.poke(false.B)
      dut.clock.step()

      // CPU makes request
      dut.memIO.req.valid.poke(true.B)
      dut.memIO.req.bits.address.poke(0x80000000L.U)
      dut.memIO.req.bits.write.poke(false.B)
      dut.memIO.req.bits.reqWidth.poke(MemWidth.WORD)
      dut.memIO.req.bits.dataWrite.foreach(_.poke(0.U))
      dut.clock.step()

      // Wishbone slave stalls
      dut.io.stall.poke(true.B)
      dut.io.ack.poke(false.B)
      dut.clock.step()

      println(s"Wishbone stb during stall: ${dut.io.stb.peek().litToBoolean}")
      println(s"Wishbone cyc during stall: ${dut.io.cyc.peek().litToBoolean}")

      // STB should remain high, CYC should remain high
      dut.io.cyc.expect(true.B, "CYC should stay high during stall")
      dut.io.stb.expect(true.B, "STB should stay high during stall")

      // Slave becomes ready
      dut.io.stall.poke(false.B)
      dut.clock.step()

      // Slave acknowledges
      dut.io.ack.poke(true.B)
      dut.io.dataToMaster.poke(0x12345678L.U)
      dut.clock.step()

      dut.memIO.resp.valid.expect(true.B, "Response should be valid")

      println("✓ Stall test passed")
    }
  }
}
