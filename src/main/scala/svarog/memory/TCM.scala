package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.SvarogConfig
import svarog.bits.asLE

// Tightly Coupled Memory (TCM)
class TCM(
    xlen: Int,
    memSizeBytes: Long,
    baseAddr: Long = 0,
    numPorts: Int = 2
) extends Module {
  require(
    memSizeBytes % (xlen / 8) == 0,
    "Memory size must be a multiple of word size"
  )
  val io = IO(new Bundle {
    val ports = Vec(numPorts, Flipped(new MemoryIO(xlen, xlen)))
  })

  val wordSize = (xlen / 8)

  private val mem =
    SyncReadMem(memSizeBytes / wordSize, Vec(wordSize, UInt(8.W)))

  for (i <- 0 until numPorts) {
    // Single cycle memory, always ready
    io.ports(i).req.ready := true.B

    val reqFire = io.ports(i).req.valid && io.ports(i).req.ready

    val addr = io.ports(i).req.bits.address
    val addrInRange =
      addr >= baseAddr
        .U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

    val wordIdx = (addr - baseAddr.U) / wordSize.U

    val enable = addrInRange && reqFire

    val readData = mem.readWrite(
      wordIdx,
      io.ports(i).req.bits.dataWrite,
      io.ports(i).req.bits.mask,
      enable,
      io.ports(i).req.bits.write
    )

    val respValid = RegNext(enable, false.B)
    io.ports(i).resp.valid := respValid
    io.ports(i).resp.bits.valid := respValid && addrInRange
    io.ports(i).resp.bits.dataRead := readData
  }
}

object TCMWishboneAdapter {
  object State extends ChiselEnum {
    val sIdle, sMem = Value
  }
}

/// Wishbone adapter for TCM
class TCMWishboneAdapter(
    xlen: Int,
    memSizeBytes: Long,
    baseAddr: Long = 0
) extends Module
    with WishboneSlave {
  import TCMWishboneAdapter.State._

  val io = IO(Flipped(new WishboneIO(addrWidth = xlen, dataWidth = xlen)))

  def addrStart: Long = baseAddr
  def addrEnd: Long = baseAddr + memSizeBytes

  private val tcm = Module(
    new TCM(
      xlen,
      memSizeBytes = memSizeBytes,
      baseAddr = baseAddr,
      numPorts = 1
    )
  )

  // Initial state - not ready to accept anything, no address asserted
  tcm.io.ports(0).req.valid := false.B
  tcm.io.ports(0).resp.ready := false.B
  tcm.io.ports(0).req.bits := 0.U.asTypeOf(new MemoryRequest(xlen, xlen))

  io.ack := false.B
  io.stall := false.B
  io.dataToMaster := 0.U
  io.error := false.B

  private val wordBytes = xlen / 8

  private val state = RegInit(sIdle)
  private val lastReq = RegInit(0.U.asTypeOf(new MemoryRequest(xlen, xlen)))

  // For testing only, TODO put under a layer?
  val stateTest = IO(Output(TCMWishboneAdapter.State.Type()))
  stateTest := state

  private def assertRequest(req: MemoryRequest) = {
    req.address := io.addr
    req.write := io.writeEnable
    req.mask := io.sel
    req.dataWrite := asLE(io.dataToSlave)
  }

  switch(state) {
    is(sIdle) {
      when(io.cycleActive && io.strobe) {
        state := sMem

        tcm.io.ports(0).req.valid := true.B
        assertRequest(tcm.io.ports(0).req.bits)
        assertRequest(lastReq)
      }
    }

    is(sMem) {
      assert(io.cycleActive)

      tcm.io.ports(0).req.valid := true.B
      tcm.io.ports(0).req.bits := lastReq
      tcm.io.ports(0).resp.ready := true.B

      // Stall until response is valid
      io.stall := !tcm.io.ports(0).resp.valid

      when(tcm.io.ports(0).resp.valid) {
        // Go to idle on the next cycle
        state := sIdle

        io.ack := true.B
        io.error := !tcm.io.ports(0).resp.bits.valid
        io.dataToMaster := tcm.io.ports(0).resp.bits.dataRead.asUInt
      }
    }
  }
}
