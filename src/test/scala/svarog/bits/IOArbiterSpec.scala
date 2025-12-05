package svarog.bits

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IOArbiterSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "IOArbiter"

  // Simple test bundles for request and response
  class TestRequest extends Bundle {
    val addr = UInt(32.W)
    val cmd = UInt(4.W)
  }

  class TestResponse extends Bundle {
    val data = UInt(32.W)
    val status = Bool()
  }

  it should "arbitrate between two sources with round-robin priority" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 2)) { dut =>
      // Initialize all signals
      dut.io.sources(0).in.valid.poke(false.B)
      dut.io.sources(1).in.valid.poke(false.B)
      dut.io.sources(0).out.ready.poke(false.B)
      dut.io.sources(1).out.ready.poke(false.B)
      dut.io.sink.ready.poke(false.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Source 0 makes a request
      dut.io.sources(0).in.bits.addr.poke(0x1000.U)
      dut.io.sources(0).in.bits.cmd.poke(0x1.U)
      dut.io.sources(0).in.valid.poke(true.B)
      dut.io.sink.ready.poke(true.B)

      dut.clock.step(1)

      // Check that source 0's request is forwarded with tag 0
      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(0.U)
      dut.io.sink.bits.data.addr.expect(0x1000.U)
      dut.io.sink.bits.data.cmd.expect(0x1.U)

      // Deassert source 0, now source 1 makes a request
      dut.io.sources(0).in.valid.poke(false.B)
      dut.io.sources(1).in.bits.addr.poke(0x2000.U)
      dut.io.sources(1).in.bits.cmd.poke(0x2.U)
      dut.io.sources(1).in.valid.poke(true.B)

      dut.clock.step(1)

      // Check that source 1's request is forwarded with tag 1
      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(1.U)
      dut.io.sink.bits.data.addr.expect(0x2000.U)
      dut.io.sink.bits.data.cmd.expect(0x2.U)
    }
  }

  it should "route responses back to the correct source based on tag" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 2)) { dut =>
      // Initialize
      dut.io.sources(0).in.valid.poke(false.B)
      dut.io.sources(1).in.valid.poke(false.B)
      dut.io.sources(0).out.ready.poke(true.B)
      dut.io.sources(1).out.ready.poke(true.B)
      dut.io.sink.ready.poke(false.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Send response for source 0 (tag = 0)
      dut.io.resp.bits.tag.poke(0.U)
      dut.io.resp.bits.data.data.poke(0xCAFE.U)
      dut.io.resp.bits.data.status.poke(true.B)
      dut.io.resp.valid.poke(true.B)

      dut.clock.step(1)

      // Check that response appears on source 0's output port only
      dut.io.sources(0).out.valid.expect(true.B)
      dut.io.sources(0).out.bits.data.expect(0xCAFE.U)
      dut.io.sources(0).out.bits.status.expect(true.B)
      dut.io.sources(1).out.valid.expect(false.B)

      // Send response for source 1 (tag = 1)
      dut.io.resp.bits.tag.poke(1.U)
      dut.io.resp.bits.data.data.poke(0xBABE.U)
      dut.io.resp.bits.data.status.poke(false.B)

      dut.clock.step(1)

      // Check that response appears on source 1's output port only
      dut.io.sources(0).out.valid.expect(false.B)
      dut.io.sources(1).out.valid.expect(true.B)
      dut.io.sources(1).out.bits.data.expect(0xBABE.U)
      dut.io.sources(1).out.bits.status.expect(false.B)
    }
  }

  it should "handle concurrent requests from multiple sources" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 3)) { dut =>
      // Initialize
      dut.io.sources.foreach { port =>
        port.in.valid.poke(false.B)
        port.out.ready.poke(true.B)
      }
      dut.io.sink.ready.poke(true.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // All three sources make requests simultaneously
      dut.io.sources(0).in.bits.addr.poke(0x1000.U)
      dut.io.sources(0).in.bits.cmd.poke(0xA.U)
      dut.io.sources(0).in.valid.poke(true.B)

      dut.io.sources(1).in.bits.addr.poke(0x2000.U)
      dut.io.sources(1).in.bits.cmd.poke(0xB.U)
      dut.io.sources(1).in.valid.poke(true.B)

      dut.io.sources(2).in.bits.addr.poke(0x3000.U)
      dut.io.sources(2).in.bits.cmd.poke(0xC.U)
      dut.io.sources(2).in.valid.poke(true.B)

      dut.clock.step(1)

      // Arbiter should pick one (lowest priority, which is source 0)
      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(0.U)
      dut.io.sink.bits.data.addr.expect(0x1000.U)
      dut.io.sink.bits.data.cmd.expect(0xA.U)
    }
  }

  it should "handle back-to-back requests from a single source" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 2)) { dut =>
      // Initialize
      dut.io.sources.foreach { port =>
        port.in.valid.poke(false.B)
        port.out.ready.poke(true.B)
      }
      dut.io.sink.ready.poke(true.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Source 0 makes first request
      dut.io.sources(0).in.bits.addr.poke(0x1000.U)
      dut.io.sources(0).in.bits.cmd.poke(0x1.U)
      dut.io.sources(0).in.valid.poke(true.B)

      dut.clock.step(1)

      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(0.U)
      dut.io.sink.bits.data.addr.expect(0x1000.U)
      dut.io.sink.bits.data.cmd.expect(0x1.U)

      // Source 0 makes second request immediately
      dut.io.sources(0).in.bits.addr.poke(0x1004.U)
      dut.io.sources(0).in.bits.cmd.poke(0x2.U)

      dut.clock.step(1)

      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(0.U)
      dut.io.sink.bits.data.addr.expect(0x1004.U)
      dut.io.sink.bits.data.cmd.expect(0x2.U)
    }
  }

  it should "properly handle 4 sources with correct tag width" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 4)) { dut =>
      // Initialize
      dut.io.sources.foreach { port =>
        port.in.valid.poke(false.B)
        port.out.ready.poke(true.B)
      }
      dut.io.sink.ready.poke(true.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Test each source individually
      for (i <- 0 until 4) {
        dut.io.sources(i).in.bits.addr.poke((0x1000 * (i + 1)).U)
        dut.io.sources(i).in.bits.cmd.poke(i.U)
        dut.io.sources(i).in.valid.poke(true.B)

        dut.clock.step(1)

        dut.io.sink.valid.expect(true.B)
        dut.io.sink.bits.tag.expect(i.U)
        dut.io.sink.bits.data.addr.expect((0x1000 * (i + 1)).U)
        dut.io.sink.bits.data.cmd.expect(i.U)

        dut.io.sources(i).in.valid.poke(false.B)
        dut.clock.step(1)
      }
    }
  }

  it should "respect backpressure from sink" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 2)) { dut =>
      // Initialize with sink not ready
      dut.io.sources(0).in.valid.poke(false.B)
      dut.io.sources(1).in.valid.poke(false.B)
      dut.io.sources(0).out.ready.poke(false.B)
      dut.io.sources(1).out.ready.poke(false.B)
      dut.io.sink.ready.poke(false.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Source 0 makes a request but sink is not ready
      dut.io.sources(0).in.bits.addr.poke(0x1000.U)
      dut.io.sources(0).in.bits.cmd.poke(0x1.U)
      dut.io.sources(0).in.valid.poke(true.B)

      dut.clock.step(1)

      // Request should be visible but sources should not be ready
      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(0.U)
      dut.io.sources(0).in.ready.expect(false.B)

      // Now make sink ready
      dut.io.sink.ready.poke(true.B)
      dut.clock.step(1)

      // Handshake should complete
      dut.io.sources(0).in.ready.expect(true.B)
    }
  }

  it should "handle interleaved requests and responses" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 2)) { dut =>
      // Initialize
      dut.io.sources.foreach { port =>
        port.in.valid.poke(false.B)
        port.out.ready.poke(true.B)
      }
      dut.io.sink.ready.poke(true.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Source 0 makes request
      dut.io.sources(0).in.bits.addr.poke(0x1000.U)
      dut.io.sources(0).in.bits.cmd.poke(0x1.U)
      dut.io.sources(0).in.valid.poke(true.B)

      dut.clock.step(1)

      // Verify request forwarded with tag 0
      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(0.U)

      // Source 1 makes request while response comes back for source 0
      dut.io.sources(0).in.valid.poke(false.B)
      dut.io.sources(1).in.bits.addr.poke(0x2000.U)
      dut.io.sources(1).in.bits.cmd.poke(0x2.U)
      dut.io.sources(1).in.valid.poke(true.B)

      dut.io.resp.bits.tag.poke(0.U)
      dut.io.resp.bits.data.data.poke(0xABCD.U)
      dut.io.resp.bits.data.status.poke(true.B)
      dut.io.resp.valid.poke(true.B)

      dut.clock.step(1)

      // Check source 1's request forwarded and source 0's response arrives
      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(1.U)
      dut.io.sources(0).out.valid.expect(true.B)
      dut.io.sources(0).out.bits.data.expect(0xABCD.U)
      dut.io.sources(0).out.bits.status.expect(true.B)
    }
  }

  it should "only assert valid on the correct source port for responses" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 4)) { dut =>
      // Initialize
      dut.io.sources.foreach { port =>
        port.in.valid.poke(false.B)
        port.out.ready.poke(true.B)
      }
      dut.io.sink.ready.poke(false.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Send response for source 2 (tag = 2)
      dut.io.resp.bits.tag.poke(2.U)
      dut.io.resp.bits.data.data.poke(0x5678.U)
      dut.io.resp.bits.data.status.poke(true.B)
      dut.io.resp.valid.poke(true.B)

      dut.clock.step(1)

      // Only source 2 should see valid response
      dut.io.sources(0).out.valid.expect(false.B)
      dut.io.sources(1).out.valid.expect(false.B)
      dut.io.sources(2).out.valid.expect(true.B)
      dut.io.sources(2).out.bits.data.expect(0x5678.U)
      dut.io.sources(2).out.bits.status.expect(true.B)
      dut.io.sources(3).out.valid.expect(false.B)
    }
  }

  it should "handle single source (n=1) as a pass-through with tag 0" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 1)) { dut =>
      // Initialize
      dut.io.sources(0).in.valid.poke(false.B)
      dut.io.sources(0).out.ready.poke(true.B)
      dut.io.sink.ready.poke(true.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      // Make a request
      dut.io.sources(0).in.bits.addr.poke(0x9000.U)
      dut.io.sources(0).in.bits.cmd.poke(0xF.U)
      dut.io.sources(0).in.valid.poke(true.B)

      dut.clock.step(1)

      // Should forward with tag 0
      dut.io.sink.valid.expect(true.B)
      dut.io.sink.bits.tag.expect(0.U)
      dut.io.sink.bits.data.addr.expect(0x9000.U)
      dut.io.sink.bits.data.cmd.expect(0xF.U)

      // Send response
      dut.io.sources(0).in.valid.poke(false.B)
      dut.io.resp.bits.tag.poke(0.U)
      dut.io.resp.bits.data.data.poke(0x1234.U)
      dut.io.resp.bits.data.status.poke(false.B)
      dut.io.resp.valid.poke(true.B)

      dut.clock.step(1)

      // Should receive response
      dut.io.sources(0).out.valid.expect(true.B)
      dut.io.sources(0).out.bits.data.expect(0x1234.U)
      dut.io.sources(0).out.bits.status.expect(false.B)
    }
  }

  it should "maintain data integrity across multiple transactions" in {
    simulate(new IOArbiter(new TestRequest, new TestResponse, n = 3)) { dut =>
      // Initialize
      dut.io.sources.foreach { port =>
        port.in.valid.poke(false.B)
        port.out.ready.poke(true.B)
      }
      dut.io.sink.ready.poke(true.B)
      dut.io.resp.valid.poke(false.B)
      dut.clock.step(1)

      val testVectors = Seq(
        (0, 0x1000, 0x1, 0xDDD1, true),
        (1, 0x2000, 0x2, 0xEEE2, false),
        (2, 0x3000, 0x3, 0xFFF3, true),
        (0, 0x1004, 0x4, 0xDDD4, false),
        (1, 0x2004, 0x5, 0xEEE5, true)
      )

      for ((sourceId, addr, cmd, respData, respStatus) <- testVectors) {
        // Send request
        dut.io.sources(sourceId).in.bits.addr.poke(addr.U)
        dut.io.sources(sourceId).in.bits.cmd.poke(cmd.U)
        dut.io.sources(sourceId).in.valid.poke(true.B)

        dut.clock.step(1)

        // Verify request
        dut.io.sink.valid.expect(true.B)
        dut.io.sink.bits.tag.expect(sourceId.U)
        dut.io.sink.bits.data.addr.expect(addr.U)
        dut.io.sink.bits.data.cmd.expect(cmd.U)

        // Clear request, send response
        dut.io.sources(sourceId).in.valid.poke(false.B)
        dut.io.resp.bits.tag.poke(sourceId.U)
        dut.io.resp.bits.data.data.poke(respData.U)
        dut.io.resp.bits.data.status.poke(respStatus.B)
        dut.io.resp.valid.poke(true.B)

        dut.clock.step(1)

        // Verify response
        dut.io.sources(sourceId).out.valid.expect(true.B)
        dut.io.sources(sourceId).out.bits.data.expect(respData.U)
        dut.io.sources(sourceId).out.bits.status.expect(respStatus.B)

        dut.io.resp.valid.poke(false.B)
        dut.clock.step(1)
      }
    }
  }
}
