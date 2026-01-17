package svarog.debug

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec
import svarog.bits.{Uart, UartRx, UartTx}

class UARTSpec extends AnyFunSpec with ChiselSim {
  describe("UartTx") {
    it("should transmit a byte with correct framing") {
      simulate(new UartTx(dataWidth = 8)) { dut =>
        // Initialize
        dut.io.baudDivider.poke(3)
        dut.io.channel.valid.poke(false)
        dut.clock.step(1)

        // TX line should be idle high
        dut.io.txd.expect(true)
        dut.io.channel.ready.expect(true)

        // Send byte 0xA5 (10100101)
        dut.io.channel.valid.poke(true)
        dut.io.channel.bits.poke(0xa5)
        dut.clock.step(1)

        // Should have accepted data and started transmission
        dut.io.channel.ready.expect(false)

        // Start bit (should be low)
        for (_ <- 0 to 3) {
          dut.io.txd.expect(false)
          dut.clock.step(1)
        }

        // Data bits (LSB first): 1, 0, 1, 0, 0, 1, 0, 1
        val expectedBits =
          Seq(true, false, true, false, false, true, false, true)
        for (bit <- expectedBits) {
          for (_ <- 0 to 3) {
            dut.io.txd.expect(bit)
            dut.clock.step(1)
          }
        }

        // Stop bit (should be high)
        for (_ <- 0 to 3) {
          dut.io.txd.expect(true)
          dut.clock.step(1)
        }

        // Should be back to idle
        dut.io.channel.ready.expect(true)
        dut.io.txd.expect(true)
      }
    }

    it("should handle back-to-back transmissions") {
      simulate(new UartTx(dataWidth = 8)) { dut =>
        dut.io.baudDivider.poke(1)
        dut.io.channel.valid.poke(false)
        dut.clock.step(1)

        // Send first byte
        dut.io.channel.valid.poke(true)
        dut.io.channel.bits.poke(0x55)
        dut.clock.step(1)

        // Wait for transmission to complete (1 start + 8 data + 1 stop) * 2 cycles per bit
        for (_ <- 0 until 20) {
          dut.clock.step(1)
        }

        // Should be ready for next byte
        dut.io.channel.ready.expect(true)

        // Send second byte immediately
        dut.io.channel.valid.poke(true)
        dut.io.channel.bits.poke(0xaa)
        dut.clock.step(1)

        // Should start transmission
        dut.io.channel.ready.expect(false)
      }
    }
  }

  describe("UartRx") {
    it("should receive a byte with correct framing") {
      simulate(new UartRx(dataWidth = 8)) { dut =>
        // Initialize
        dut.io.baudDivider.poke(3)
        dut.io.rxd.poke(true)
        dut.io.channel.ready.poke(false)

        // Wait for synchronizer
        dut.clock.step(5)

        dut.io.channel.valid.expect(false)

        // Send byte 0xA5 (10100101) - LSB first
        // Start bit
        dut.io.rxd.poke(false)
        for (_ <- 0 until 4) {
          dut.clock.step(1)
        }

        // Data bits: 1, 0, 1, 0, 0, 1, 0, 1
        val dataBits = Seq(true, false, true, false, false, true, false, true)
        for (bit <- dataBits) {
          dut.io.rxd.poke(bit)
          for (_ <- 0 until 4) {
            dut.clock.step(1)
          }
        }

        // Stop bit
        dut.io.rxd.poke(true)
        for (_ <- 0 until 4) {
          dut.clock.step(1)
        }

        // Wait a bit more for processing
        dut.clock.step(2)

        // Check received data
        dut.io.channel.valid.expect(true)
        dut.io.channel.bits.expect(0xa5)

        // Read the data
        dut.io.channel.ready.poke(true)
        dut.clock.step(1)
        dut.io.channel.valid.expect(false)
      }
    }

    it("should reject invalid start bit") {
      simulate(new UartRx(dataWidth = 8)) { dut =>
        dut.io.baudDivider.poke(3)
        dut.io.rxd.poke(true)
        dut.io.channel.ready.poke(true)

        // Wait for synchronizer
        dut.clock.step(3)

        // Glitch on RX line (not a valid start bit)
        dut.io.rxd.poke(false)
        dut.clock.step(1)
        dut.io.rxd.poke(true)
        dut.clock.step(1)

        // Wait half a bit period
        for (_ <- 0 to 1) {
          dut.clock.step(1)
        }

        // Should have returned to idle without receiving data
        dut.io.channel.valid.expect(false)
      }
    }
  }

  describe("Uart") {
    it("should support simultaneous TX and RX") {
      simulate(new Uart(dataWidth = 8)) { dut =>
        dut.io.baudDivider.poke(3)
        dut.io.rxd.poke(true)
        dut.io.tx.valid.poke(false)
        dut.io.rx.ready.poke(false)

        dut.clock.step(5)

        // Start transmitting 0x12
        dut.io.tx.valid.poke(true)
        dut.io.tx.bits.poke(0x12)
        dut.clock.step(1)

        // Simultaneously receive 0x34
        dut.io.rxd.poke(false) // Start bit
        for (_ <- 0 until 4) {
          dut.clock.step(1)
        }

        // Data bits for 0x34 (00110100 -> LSB first: 0,0,1,0,1,1,0,0)
        val rxBits = Seq(false, false, true, false, true, true, false, false)
        for (bit <- rxBits) {
          dut.io.rxd.poke(bit)
          for (_ <- 0 until 4) {
            dut.clock.step(1)
          }
        }

        // Stop bit
        dut.io.rxd.poke(true)
        for (_ <- 0 until 4) {
          dut.clock.step(1)
        }

        // Wait for processing
        dut.clock.step(2)

        // Check that data was received
        dut.io.rx.valid.expect(true)
        dut.io.rx.bits.expect(0x34)
      }
    }
  }
}
