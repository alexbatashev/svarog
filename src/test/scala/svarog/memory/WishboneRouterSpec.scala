package svarog.memory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WishboneRouterSpec extends AnyFlatSpec with Matchers with ChiselSim {

  behavior of "WishboneRouter"

  it should "route a single master to a single slave" in {
    simulate(new WishboneRouter(
      numMasters = 1,
      numSlaves = 1,
      slaveAddrRanges = Seq((0x80000000L, 0x80004000L)),
      addrWidth = 32,
      dataWidth = 32
    )) { dut =>
      // Initial state: everything idle
      dut.io.masters(0).cyc.poke(false.B)
      dut.io.masters(0).stb.poke(false.B)
      dut.io.slaves(0).ack.poke(false.B)
      dut.io.slaves(0).stall.poke(false.B)
      dut.clock.step()

      println("=== Test: Single master read transaction ===")

      // Master initiates read transaction
      dut.io.masters(0).cyc.poke(true.B)
      dut.io.masters(0).stb.poke(true.B)
      dut.io.masters(0).we.poke(false.B)
      dut.io.masters(0).addr.poke(0x80000004L.U)
      dut.io.masters(0).sel.foreach(_.poke(true.B))
      dut.clock.step()

      // Check: Request should be routed to slave
      dut.io.slaves(0).cyc.expect(true.B, "Slave should see CYC")
      dut.io.slaves(0).stb.expect(true.B, "Slave should see STB")
      dut.io.slaves(0).we.expect(false.B, "Slave should see WE=0 (read)")
      dut.io.slaves(0).addr.expect(0x80000004L.U, "Slave should see correct address")

      // Check: Master should not be stalled (request accepted)
      val masterStall = dut.io.masters(0).stall.peek().litToBoolean
      println(s"Master stall after request: $masterStall")
      dut.io.masters(0).stall.expect(false.B, "Master should not be stalled when slave is ready")

      // Slave acknowledges
      dut.io.slaves(0).ack.poke(true.B)
      dut.io.slaves(0).stall.poke(false.B)
      dut.io.slaves(0).dataToMaster.poke(0xDEADBEEFL.U)
      dut.clock.step()

      // Check: ACK and data should be routed back to master
      dut.io.masters(0).ack.expect(true.B, "Master should see ACK")
      dut.io.masters(0).dataToMaster.expect(0xDEADBEEFL.U, "Master should see data")

      // Master deasserts CYC/STB
      dut.io.masters(0).cyc.poke(false.B)
      dut.io.masters(0).stb.poke(false.B)
      dut.io.slaves(0).ack.poke(false.B)
      dut.clock.step()

      println("✓ Single master test passed")
    }
  }

  it should "handle slave backpressure (stall)" in {
    simulate(new WishboneRouter(
      numMasters = 1,
      numSlaves = 1,
      slaveAddrRanges = Seq((0x80000000L, 0x80004000L)),
      addrWidth = 32,
      dataWidth = 32
    )) { dut =>
      println("=== Test: Slave backpressure ===")

      // Initial state
      dut.io.masters(0).cyc.poke(false.B)
      dut.io.masters(0).stb.poke(false.B)
      dut.io.slaves(0).ack.poke(false.B)
      dut.io.slaves(0).stall.poke(false.B)
      dut.clock.step()

      // Master initiates transaction
      dut.io.masters(0).cyc.poke(true.B)
      dut.io.masters(0).stb.poke(true.B)
      dut.io.masters(0).we.poke(false.B)
      dut.io.masters(0).addr.poke(0x80000000L.U)
      dut.io.masters(0).sel.foreach(_.poke(true.B))

      // Slave is busy (stall)
      dut.io.slaves(0).stall.poke(true.B)
      dut.io.slaves(0).ack.poke(false.B)
      dut.clock.step()

      // Check: Master should see stall
      println(s"Master stall when slave stalls: ${dut.io.masters(0).stall.peek().litToBoolean}")
      dut.io.masters(0).stall.expect(true.B, "Master should be stalled when slave stalls")
      dut.io.masters(0).ack.expect(false.B, "Master should not see ACK while stalled")

      // Slave becomes ready
      dut.io.slaves(0).stall.poke(false.B)
      dut.clock.step()

      // Check: Master should not be stalled
      println(s"Master stall when slave ready: ${dut.io.masters(0).stall.peek().litToBoolean}")
      dut.io.masters(0).stall.expect(false.B, "Master should not be stalled when slave is ready")

      // Slave acknowledges
      dut.io.slaves(0).ack.poke(true.B)
      dut.io.slaves(0).dataToMaster.poke(0x12345678L.U)
      dut.clock.step()

      dut.io.masters(0).ack.expect(true.B, "Master should see ACK")
      dut.io.masters(0).dataToMaster.expect(0x12345678L.U, "Master should see data")

      println("✓ Backpressure test passed")
    }
  }

  it should "arbitrate between two masters" in {
    simulate(new WishboneRouter(
      numMasters = 2,
      numSlaves = 1,
      slaveAddrRanges = Seq((0x80000000L, 0x80004000L)),
      addrWidth = 32,
      dataWidth = 32
    )) { dut =>
      println("=== Test: Two masters arbitration ===")

      // Initial state
      dut.io.masters.foreach { m =>
        m.cyc.poke(false.B)
        m.stb.poke(false.B)
      }
      dut.io.slaves(0).ack.poke(false.B)
      dut.io.slaves(0).stall.poke(false.B)
      dut.clock.step()

      // Master 0 makes request
      println("Master 0 requests")
      dut.io.masters(0).cyc.poke(true.B)
      dut.io.masters(0).stb.poke(true.B)
      dut.io.masters(0).addr.poke(0x80000000L.U)
      dut.io.masters(0).sel.foreach(_.poke(true.B))
      dut.clock.step()

      // Check: Master 0 should be granted, Master 1 idle (no stall since not requesting)
      println(s"Master 0 stall: ${dut.io.masters(0).stall.peek().litToBoolean}")
      println(s"Master 1 stall: ${dut.io.masters(1).stall.peek().litToBoolean}")
      dut.io.masters(0).stall.expect(false.B, "Master 0 should be granted")
      dut.io.masters(1).stall.expect(false.B, "Master 1 idle, should not be stalled")

      // Slave responds
      dut.io.slaves(0).ack.poke(true.B)
      dut.io.slaves(0).dataToMaster.poke(0xAAAAAAAAL.U)
      dut.clock.step()

      dut.io.masters(0).ack.expect(true.B, "Master 0 should see ACK")
      dut.io.masters(1).ack.expect(false.B, "Master 1 should not see ACK")

      // Master 0 releases bus
      dut.io.masters(0).cyc.poke(false.B)
      dut.io.masters(0).stb.poke(false.B)
      dut.io.slaves(0).ack.poke(false.B)
      dut.clock.step()

      // Now Master 1 requests
      println("Master 1 requests")
      dut.io.masters(1).cyc.poke(true.B)
      dut.io.masters(1).stb.poke(true.B)
      dut.io.masters(1).addr.poke(0x80000100L.U)
      dut.io.masters(1).sel.foreach(_.poke(true.B))
      dut.clock.step()

      // Check: Master 1 should be granted
      println(s"Master 1 stall: ${dut.io.masters(1).stall.peek().litToBoolean}")
      dut.io.masters(1).stall.expect(false.B, "Master 1 should be granted")

      // Slave responds
      dut.io.slaves(0).ack.poke(true.B)
      dut.io.slaves(0).dataToMaster.poke(0xBBBBBBBBL.U)
      dut.clock.step()

      dut.io.masters(1).ack.expect(true.B, "Master 1 should see ACK")
      dut.io.masters(1).dataToMaster.expect(0xBBBBBBBBL.U, "Master 1 should see data")

      println("✓ Arbitration test passed")
    }
  }

  it should "handle concurrent requests from two masters (CPU scenario)" in {
    simulate(new WishboneRouter(
      numMasters = 2,
      numSlaves = 1,
      slaveAddrRanges = Seq((0x80000000L, 0x80004000L)),
      addrWidth = 32,
      dataWidth = 32
    )) { dut =>
      println("=== Test: Concurrent masters (CPU scenario) ===")

      // Initial state
      dut.io.masters.foreach { m =>
        m.cyc.poke(false.B)
        m.stb.poke(false.B)
      }
      dut.io.slaves(0).ack.poke(false.B)
      dut.io.slaves(0).stall.poke(false.B)
      dut.clock.step()

      // Both masters try to access at once (instruction fetch + data access)
      println("Both masters request simultaneously")
      dut.io.masters(0).cyc.poke(true.B)
      dut.io.masters(0).stb.poke(true.B)
      dut.io.masters(0).we.poke(false.B)
      dut.io.masters(0).addr.poke(0x80000000L.U)
      dut.io.masters(0).sel.foreach(_.poke(true.B))

      dut.io.masters(1).cyc.poke(true.B)
      dut.io.masters(1).stb.poke(true.B)
      dut.io.masters(1).we.poke(false.B)
      dut.io.masters(1).addr.poke(0x80000100L.U)
      dut.io.masters(1).sel.foreach(_.poke(true.B))

      dut.clock.step()

      // One should be granted, one should be stalled
      val master0Stall = dut.io.masters(0).stall.peek().litToBoolean
      val master1Stall = dut.io.masters(1).stall.peek().litToBoolean
      println(s"Master 0 stall: $master0Stall, Master 1 stall: $master1Stall")

      // Exactly one should be stalled
      assert(master0Stall != master1Stall, "Exactly one master should be stalled")

      val grantedMaster = if (!master0Stall) 0 else 1
      val stalledMaster = if (master0Stall) 0 else 1

      println(s"Master $grantedMaster granted, Master $stalledMaster stalled")

      // Slave acknowledges granted master
      dut.io.slaves(0).ack.poke(true.B)
      dut.io.slaves(0).dataToMaster.poke(0xDEADBEEFL.U)
      dut.clock.step()

      dut.io.masters(grantedMaster).ack.expect(true.B, s"Master $grantedMaster should see ACK")
      dut.io.masters(stalledMaster).ack.expect(false.B, s"Master $stalledMaster should not see ACK")

      // Granted master completes
      dut.io.masters(grantedMaster).cyc.poke(false.B)
      dut.io.masters(grantedMaster).stb.poke(false.B)
      dut.io.slaves(0).ack.poke(false.B)
      dut.clock.step()

      // Now the stalled master should be granted
      println(s"Master $stalledMaster should now be granted")
      dut.io.masters(stalledMaster).stall.expect(false.B, s"Master $stalledMaster should now be granted")

      // Slave acknowledges
      dut.io.slaves(0).ack.poke(true.B)
      dut.io.slaves(0).dataToMaster.poke(0xCAFEBABEL.U)
      dut.clock.step()

      dut.io.masters(stalledMaster).ack.expect(true.B, s"Master $stalledMaster should see ACK")

      println("✓ Concurrent masters test passed")
    }
  }
}
