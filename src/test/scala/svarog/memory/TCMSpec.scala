package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class TCMSpec extends AnyFunSpec with ChiselSim {
  describe("TCMWishboneAdapter") {
    it("should correctly respond to stimulus") {
      simulate(new TCMWishboneAdapter(32, 128)) { dut =>
        import TCMWishboneAdapter.State._

        dut.stateTest.expect(sIdle)

        dut.clock.step(1)

        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.addr.poke(0x4)
        dut.io.writeEnable.poke(true)
        dut.io.sel.foreach(_.poke(true))
        dut.io.dataToSlave.poke(42)

        dut.stateTest.expect(sIdle)

        dut.clock.step(1)

        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)

        dut.stateTest.expect(sMem)
        dut.io.ack.expect(true)
        dut.io.error.expect(false)

        dut.clock.step(1)

        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)
        dut.io.addr.poke(0x4)
        dut.io.writeEnable.poke(false)
        dut.io.sel.foreach(_.poke(true))
        dut.io.dataToSlave.poke(0)

        dut.stateTest.expect(sIdle)
        dut.io.ack.expect(false)
        dut.io.error.expect(false)

        dut.clock.step(1)

        dut.io.cycleActive.poke(true)
        dut.io.strobe.poke(true)

        dut.stateTest.expect(sMem)
        dut.io.ack.expect(true)
        dut.io.error.expect(false)
        dut.io.dataToMaster.expect(42)
      }
    }
  }
}
