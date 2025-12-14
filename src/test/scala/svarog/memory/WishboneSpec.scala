package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.funspec.AnyFunSpec

private class WishboneBench extends Module {
  val io = IO(new Bundle {
    val mem1 = Flipped(new MemoryIO(32, 32))
    val mem2 = Flipped(new MemoryIO(32, 32))
    val state1 = Output(MemWishboneHost.State.Type())
    val state2 = Output(MemWishboneHost.State.Type())
  })

  private val tcm = Module(new TCMWishboneAdapter(32, 128))

  private val mem1 = Module(new MemWishboneHost(32, 32))
  private val mem2 = Module(new MemWishboneHost(32, 32))

  io.state1 := mem1.stateTest
  io.state2 := mem2.stateTest

  // Debug TCM state
  printf(cf"TCM state: ${tcm.stateTest}\n")

  WishboneRouter(Seq(mem1, mem2), Seq(tcm))

  mem1.mem.req <> io.mem1.req
  mem1.mem.resp <> io.mem1.resp
  mem2.mem.req <> io.mem2.req
  mem2.mem.resp <> io.mem2.resp
}

class WishboneSpec extends AnyFunSpec with ChiselSim {
  describe("WishboneBus") {
    it("should correctly go through masters") {
      simulate(new WishboneBench) { dut =>
        // Initialize all signals
        dut.io.mem1.req.valid.poke(false)
        dut.io.mem2.req.valid.poke(false)
        dut.io.mem1.resp.ready.poke(true)
        dut.io.mem2.resp.ready.poke(true)

        dut.clock.step(1)

        // Cycle 0: Master 0 (mem1) writes 0xCAFEBABE to address 0x4
        dut.io.mem1.req.valid.poke(true)
        dut.io.mem1.req.bits.address.poke(0x4)
        dut.io.mem1.req.bits.write.poke(true)
        // Write data as bytes
        dut.io.mem1.req.bits.dataWrite(0).poke(0xbe)
        dut.io.mem1.req.bits.dataWrite(1).poke(0xba)
        dut.io.mem1.req.bits.dataWrite(2).poke(0xfe)
        dut.io.mem1.req.bits.dataWrite(3).poke(0xca)
        dut.io.mem1.req.bits.mask.foreach(_.poke(true))

        dut.clock.step(1)

        // Keep request valid until response arrives
        dut.io.mem1.req.valid.poke(true)
        dut.io.mem1.req.bits.address.poke(0x4)
        dut.io.mem1.req.bits.write.poke(true)
        dut.io.mem1.req.bits.dataWrite(0).poke(0xbe)
        dut.io.mem1.req.bits.dataWrite(1).poke(0xba)
        dut.io.mem1.req.bits.dataWrite(2).poke(0xfe)
        dut.io.mem1.req.bits.dataWrite(3).poke(0xca)
        dut.io.mem1.req.bits.mask.foreach(_.poke(true))

        dut.io.mem1.resp.ready.expect(true)
        dut.io.mem1.resp.valid.expect(true)
        println(s"After write: state1=${dut.io.state1.peek()}, state2=${dut.io.state2.peek()}")

        // Cycle 2: Both masters try to read from address 0x4
        dut.io.mem1.req.valid.poke(true)
        dut.io.mem1.req.bits.address.poke(0x4)
        dut.io.mem1.req.bits.write.poke(false)
        dut.io.mem1.req.bits.mask.foreach(_.poke(true))

        dut.io.mem2.req.valid.poke(true)
        dut.io.mem2.req.bits.address.poke(0x4)
        dut.io.mem2.req.bits.write.poke(false)
        dut.io.mem2.req.bits.mask.foreach(_.poke(true))

        println(s"Before step: state1=${dut.io.state1.peek()}, state2=${dut.io.state2.peek()}")

        dut.clock.step(1)

        println(s"After step 1 (cooldown): state1=${dut.io.state1.peek()}, state2=${dut.io.state2.peek()}")

        // Step again - master 1 exits cooldown, master 2 gets granted and responds
        dut.io.mem1.req.valid.poke(true)
        dut.io.mem1.req.bits.address.poke(0x4)
        dut.io.mem1.req.bits.write.poke(false)
        dut.io.mem1.req.bits.mask.foreach(_.poke(true))

        dut.io.mem2.req.valid.poke(true)
        dut.io.mem2.req.bits.address.poke(0x4)
        dut.io.mem2.req.bits.write.poke(false)
        dut.io.mem2.req.bits.mask.foreach(_.poke(true))

        dut.clock.step(1)

        println(s"After step 2 (master 2 active): state1=${dut.io.state1.peek()}, state2=${dut.io.state2.peek()}")

        // Keep requests valid
        dut.io.mem1.req.valid.poke(true)
        dut.io.mem1.req.bits.address.poke(0x4)
        dut.io.mem1.req.bits.write.poke(false)
        dut.io.mem1.req.bits.mask.foreach(_.poke(true))

        dut.io.mem2.resp.ready.expect(true)
        dut.io.mem2.req.valid.poke(true)
        dut.io.mem2.req.bits.address.poke(0x4)
        dut.io.mem2.req.bits.write.poke(false)
        dut.io.mem2.req.bits.mask.foreach(_.poke(true))

        dut.io.mem1.resp.valid.expect(false)
        dut.io.mem2.resp.valid.expect(true)
        dut.io.mem2.resp.bits.dataRead(0).expect(0xbe)
        dut.io.mem2.resp.bits.dataRead(1).expect(0xba)
        dut.io.mem2.resp.bits.dataRead(2).expect(0xfe)
        dut.io.mem2.resp.bits.dataRead(3).expect(0xca)

        // println(s"After first read cycle: mem1.resp.valid=${dut.io.mem1.resp.valid.peek()}, mem2.resp.valid=${dut.io.mem2.resp.valid.peek()}")

        // dut.clock.step(1)

        // // Keep requests valid
        // dut.io.mem1.req.valid.poke(true)
        // dut.io.mem1.req.bits.address.poke(0x4)
        // dut.io.mem1.req.bits.write.poke(false)
        // dut.io.mem1.req.bits.mask.foreach(_.poke(true))

        // dut.io.mem2.req.valid.poke(true)
        // dut.io.mem2.req.bits.address.poke(0x4)
        // dut.io.mem2.req.bits.write.poke(false)
        // dut.io.mem2.req.bits.mask.foreach(_.poke(true))

        // println(s"After second read cycle: mem1.resp.valid=${dut.io.mem1.resp.valid.peek()}, mem2.resp.valid=${dut.io.mem2.resp.valid.peek()}")

        // // Master 1 (mem2) should complete first due to round robin
        // dut.io.mem2.resp.valid.expect(true)
        // dut.io.mem2.resp.bits.dataRead(0).expect(0xBE)
        // dut.io.mem2.resp.bits.dataRead(1).expect(0xBA)
        // dut.io.mem2.resp.bits.dataRead(2).expect(0xFE)
        // dut.io.mem2.resp.bits.dataRead(3).expect(0xCA)

        // // Master 0 (mem1) should not complete yet
        // dut.io.mem1.resp.valid.expect(false)

        // // Keep mem2 request low, mem1 still high
        // dut.io.mem2.req.valid.poke(false)

        // dut.clock.step(1)

        // // Keep mem1 request valid
        // dut.io.mem1.req.valid.poke(true)
        // dut.io.mem1.req.bits.address.poke(0x4)
        // dut.io.mem1.req.bits.write.poke(false)
        // dut.io.mem1.req.bits.mask.foreach(_.poke(true))

        // println(s"After second read cycle: mem1.resp.valid=${dut.io.mem1.resp.valid.peek()}")

        // // Master 0 (mem1) should complete now
        // dut.io.mem1.resp.valid.expect(true)
        // dut.io.mem1.resp.bits.dataRead(0).expect(0xBE)
        // dut.io.mem1.resp.bits.dataRead(1).expect(0xBA)
        // dut.io.mem1.resp.bits.dataRead(2).expect(0xFE)
        // dut.io.mem1.resp.bits.dataRead(3).expect(0xCA)

        // dut.io.mem1.req.valid.poke(false)

        // dut.clock.step(1)
      }
    }
  }
}
