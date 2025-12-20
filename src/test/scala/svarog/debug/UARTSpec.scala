package svarog.debug

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

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
        dut.io.channel.bits.poke(0xA5)
        dut.clock.step(1)

        // Should have accepted data and started transmission
        dut.io.channel.ready.expect(false)

        // Start bit (should be low)
        for (_ <- 0 to 3) {
          dut.io.txd.expect(false)
          dut.clock.step(1)
        }

        // Data bits (LSB first): 1, 0, 1, 0, 0, 1, 0, 1
        val expectedBits = Seq(true, false, true, false, false, true, false, true)
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
        dut.io.channel.bits.poke(0xAA)
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
        dut.io.channel.bits.expect(0xA5)

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

  describe("UartWishbone") {
    it("should write and read via Wishbone registers") {
      simulate(new UartWishbone(baseAddr = 0x80000000L)) { dut =>
        import UartWishbone._

        // Initialize
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.uart.rxd.poke(true)

        dut.clock.step(3)

        // Write baud divider
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.addr.poke(0x80000000L + BAUD_DIV_OFFSET)
        dut.io.dataToSlave.poke(100)
        dut.io.sel.foreach(_.poke(true))

        dut.clock.step(1)

        dut.io.ack.expect(true)

        // Read back baud divider
        dut.io.writeEnable.poke(false)
        dut.io.addr.poke(0x80000000L + BAUD_DIV_OFFSET)

        dut.clock.step(1)

        dut.io.ack.expect(true)
        dut.io.dataToMaster.expect(100)

        // Check status register - TX should be ready
        dut.io.addr.poke(0x80000000L + STATUS_REG_OFFSET)

        dut.clock.step(1)

        dut.io.ack.expect(true)
        dut.io.dataToMaster.expect(1) // Bit 0 = TX ready
      }
    }

    it("should transmit data via data register write") {
      simulate(new UartWishbone(baseAddr = 0x80000000L)) { dut =>
        import UartWishbone._

        // Initialize
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.uart.rxd.poke(true)

        dut.clock.step(3)

        // Set a fast baud rate for testing
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.addr.poke(0x80000000L + BAUD_DIV_OFFSET)
        dut.io.dataToSlave.poke(2)
        dut.io.sel.foreach(_.poke(true))

        dut.clock.step(1)

        // Write byte to transmit
        dut.io.addr.poke(0x80000000L + DATA_REG_OFFSET)
        dut.io.dataToSlave.poke(0x55)

        dut.clock.step(1)

        dut.io.ack.expect(true)

        // Deactivate bus
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)

        // TX line should go low (start bit)
        dut.clock.step(1)
        dut.uart.txd.expect(false)

        // Wait for start bit to complete
        for (_ <- 0 to 2) {
          dut.clock.step(1)
        }

        // First data bit of 0x55 (01010101) LSB first = 1
        dut.uart.txd.expect(true)
      }
    }

    it("should receive data and set RX valid status") {
      simulate(new UartWishbone(baseAddr = 0x80000000L)) { dut =>
        import UartWishbone._

        // Initialize
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)
        dut.uart.rxd.poke(true)

        dut.clock.step(3)

        // Set a fast baud rate
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(true)
        dut.io.addr.poke(0x80000000L + BAUD_DIV_OFFSET)
        dut.io.dataToSlave.poke(2)
        dut.io.sel.foreach(_.poke(true))

        dut.clock.step(1)

        // Deactivate bus
        dut.io.cycleActive.poke(false)
        dut.io.strobe.poke(false)

        dut.clock.step(1)

        // Send byte 0xAB via UART RX line
        // Start bit
        dut.uart.rxd.poke(false)
        for (_ <- 0 until 3) {
          dut.clock.step(1)
        }

        // Data bits for 0xAB (10101011 -> LSB first: 1,1,0,1,0,1,0,1)
        val rxBits = Seq(true, true, false, true, false, true, false, true)
        for (bit <- rxBits) {
          dut.uart.rxd.poke(bit)
          for (_ <- 0 until 3) {
            dut.clock.step(1)
          }
        }

        // Stop bit
        dut.uart.rxd.poke(true)
        for (_ <- 0 until 3) {
          dut.clock.step(1)
        }

        // Wait for processing
        dut.clock.step(2)

        // Check status register - RX should be valid
        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.writeEnable.poke(false)
        dut.io.addr.poke(0x80000000L + STATUS_REG_OFFSET)
        dut.io.sel.foreach(_.poke(true))

        dut.clock.step(1)

        dut.io.ack.expect(true)
        dut.io.dataToMaster.expect(3) // Bit 0 = TX ready, Bit 1 = RX valid

        // Read data register
        dut.io.addr.poke(0x80000000L + DATA_REG_OFFSET)

        dut.clock.step(1)

        dut.io.ack.expect(true)
        dut.io.dataToMaster.expect(0xAB)

        // After reading, RX valid should be cleared
        dut.io.addr.poke(0x80000000L + STATUS_REG_OFFSET)

        dut.clock.step(1)

        dut.io.dataToMaster.expect(1) // Only TX ready now
      }
    }
  }
}
