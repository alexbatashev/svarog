package svarog.memory

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DirectMappedCacheSpec extends AnyFlatSpec with Matchers with ChiselSim {
  behavior of "DirectMappedCache"

  val cacheSize = 256
  val lineSize = 16
  val xlen = 32
  val wordsPerLine = lineSize / 4

  it should "handle a compulsory miss using single-threaded testing" in {
    simulate(new DirectMappedCache(cacheSize, lineSize, xlen)) { dut =>
      val memoryLatency = 5
      val addr1 = 0x1000

      // --- 1. CPU makes a request ---
      dut.io.cpu.reqValid.poke(true.B)
      dut.io.cpu.addr.poke(addr1.U)
      dut.clock.step(1)
      dut.io.cpu.reqValid.poke(false.B)

      // --- 2. Observe the cache's reaction (MISS) ---
      dut.io.mem.reqValid.expect(true.B)
      // Calculate expected line address using Scala/Java operations, not Chisel
      val expectedLineAddr = addr1 & 0xFFFFFFF0
      dut.io.mem.addr.expect(expectedLineAddr.U)
      dut.io.cpu.respValid.expect(false.B)

      // --- 3. Manually simulate the memory's response ---
      dut.clock.step(memoryLatency)

      // Create fake data as Scala sequence
      val responseData = (0 until wordsPerLine).map(i => (addr1 + (i * 4)).U)

      dut.io.mem.respValid.poke(true.B)
      for (i <- 0 until wordsPerLine) {
        dut.io.mem.dataIn(i).poke(responseData(i))
      }

      dut.clock.step(1)
      dut.io.mem.respValid.poke(false.B)

      // --- 4. The original request should now be a HIT ---
      dut.io.cpu.reqValid.poke(true.B)
      dut.io.cpu.addr.poke(addr1.U)
      dut.clock.step(1)

      dut.io.cpu.respValid.expect(true.B)
      dut.io.cpu.data.expect(addr1.U)
    }
  }
}
