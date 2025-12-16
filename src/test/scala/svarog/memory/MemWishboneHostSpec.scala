package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

class MemWishboneHostSpec extends AnyFunSpec with ChiselSim {
  describe("MemWishboneHost") {
    it("transitions through the states correctly") {
      simulate(new MemWishboneHost(32, 32)) { dut =>
        dut.mem.req.valid.poke(false)
        dut.io.cycleActive.expect(false)

        dut.stateTest.expect(MemWishboneHost.State.sIdle)

        dut.clock.step(1)

        dut.mem.req.valid.poke(true)
        dut.mem.req.bits.address.poke(0xdeadbeef)
        dut.mem.req.bits.write.poke(false)
        dut.mem.req.bits.mask.foreach(_.poke(true))

        dut.io.cycleActive.expect(true)
        dut.io.strobe.expect(true)
        dut.stateTest.expect(MemWishboneHost.State.sIdle)

        dut.clock.step(1)

        dut.mem.req.valid.poke(true)
        dut.mem.req.bits.address.poke(0xdeadbeef)
        dut.mem.req.bits.write.poke(false)
        dut.mem.resp.ready.poke(true)

        dut.io.ack.poke(true)
        dut.io.dataToMaster.poke(0xdeadbeef)

        dut.mem.resp.valid.expect(true)
        dut.io.cycleActive.expect(true)
        dut.io.strobe.expect(true)
        dut.stateTest.expect(MemWishboneHost.State.sRespWait)

        dut.clock.step(1)

        dut.stateTest.expect(MemWishboneHost.State.sCooldown)

        dut.clock.step(1)

        dut.mem.req.valid.poke(false)
        dut.io.cycleActive.expect(false)
        dut.stateTest.expect(MemWishboneHost.State.sIdle)
      }
    }
  }
}
