package svarog.memory

import chisel3._
import chisel3.util._
import svarog.bits.masked
import chisel3.util.experimental.loadMemoryFromFile

/// Read-only memory
class ROM(
    xlen: Int,
    baseAddr: Long = 0,
    file: String
) extends Module {
  private val memSizeBytes = 65536

  require(
    memSizeBytes % (xlen / 8) == 0,
    "Memory size must be a multiple of word size"
  )
  val io = IO(Flipped(new MemoryIO(xlen, xlen)))

  private val wordSize = (xlen / 8)

  private val mem =
    SyncReadMem(memSizeBytes / wordSize, Vec(wordSize, UInt(8.W)))

  loadMemoryFromFile(mem, file)

  // Single cycle memory, always ready
  io.req.ready := true.B

  val addr = io.req.bits.address
  val addrInRange =
    addr >= baseAddr
      .U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

  val wordIdx = (addr - baseAddr.U) / wordSize.U

  val readData = mem.read(wordIdx)

  val enable = addrInRange && io.req.fire

  val writeEn = RegNext(io.req.bits.write)
  val respValid = RegNext(enable, false.B)
  val mask = RegNext(io.req.bits.mask, VecInit(Seq.fill(wordSize)(false.B)))

  io.resp.valid := respValid && !writeEn
  io.resp.bits.valid := respValid && addrInRange
  io.resp.bits.dataRead := masked(readData, mask)
}

object ROMWishboneAdapter {
  object State extends ChiselEnum {
    val sIdle, sMem = Value
  }
}

/// Wishbone adapter for ROM
class ROMWishboneAdapter(
    xlen: Int,
    baseAddr: Long = 0,
    file: String
) extends Module
    with WishboneSlave {
  import ROMWishboneAdapter.State._

  private val memSizeBytes = 65536

  val io = IO(Flipped(new WishboneIO(addrWidth = xlen, dataWidth = xlen)))

  def addrStart: Long = baseAddr
  def addrEnd: Long = baseAddr + memSizeBytes

  private val rom = Module(
    new ROM(xlen, baseAddr, file)
  )

  // Initial state - not ready to accept anything, no address asserted
  rom.io.req.valid := false.B
  rom.io.resp.ready := false.B
  rom.io.req.bits := 0.U.asTypeOf(new MemoryRequest(xlen, xlen))

  io.ack := false.B
  io.stall := false.B
  io.dataToMaster := 0.U
  io.error := false.B

  private val wordBytes = xlen / 8

  private val state = RegInit(sIdle)
  private val lastReq = RegInit(0.U.asTypeOf(new MemoryRequest(xlen, xlen)))

  private def assertRequest(req: MemoryRequest) = {
    req.address := io.addr
    req.write := io.writeEnable
    req.mask := io.sel
    for (i <- 0 until wordBytes) {
      req.dataWrite(i) := io.dataToSlave(8 * (i + 1) - 1, 8 * i)
    }
  }

  switch(state) {
    is(sIdle) {
      when(io.cycleActive && io.strobe) {
        state := sMem

        rom.io.req.valid := true.B
        assertRequest(rom.io.req.bits)
        assertRequest(lastReq)
      }
    }

    is(sMem) {
      assert(io.cycleActive)

      rom.io.req.valid := true.B
      rom.io.req.bits := lastReq
      rom.io.resp.ready := true.B

      // Stall until response is valid
      io.stall := !rom.io.resp.valid

      when(rom.io.resp.valid) {
        // Go to idle on the next cycle
        state := sIdle

        io.ack := true.B
        io.error := !rom.io.resp.bits.valid
        io.dataToMaster := Cat(rom.io.resp.bits.dataRead.reverse)
      }
    }
  }
}
