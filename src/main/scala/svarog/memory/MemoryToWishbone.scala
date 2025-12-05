package svarog.memory

import chisel3._
import chisel3.util._

/**
 * Adapter: MemoryIO (CPU side) to Wishbone Master
 *
 * Converts the CPU's MemoryIO interface to a Wishbone B4 pipelined master.
 * The CPU uses a simple request/response protocol, while Wishbone uses
 * cyc/stb/ack handshaking.
 */
class MemoryToWishbone(val xlen: Int = 32) extends Module with WishboneMaster {

  val memIO = IO(Flipped(new MemoryIO(xlen, xlen)))
  val io = IO(new WishboneIO(addrWidth = xlen, dataWidth = xlen))

  // ============================================================================
  // State Machine for Transaction Control
  // ============================================================================

  val sIdle :: sActive :: sWaitAck :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // ============================================================================
  // MemoryIO -> Wishbone Request
  // ============================================================================

  // Accept new request when idle
  memIO.req.ready := state === sIdle

  // Debug: log a few requests
  val dbgPrintsLeft = RegInit(8.U(4.W))
  when(memIO.req.fire && dbgPrintsLeft =/= 0.U) {
    printf(
      p"[M2WB] fire addr=0x${Hexadecimal(memIO.req.bits.address)} we=${memIO.req.bits.write} width=${memIO.req.bits.reqWidth} data=0x${Hexadecimal(memIO.req.bits.dataWrite.asUInt)}\n"
    )
    dbgPrintsLeft := dbgPrintsLeft - 1.U
  }

  // Latch request when accepted
  val reqAddr = RegEnable(memIO.req.bits.address, memIO.req.fire)
  val reqWe = RegEnable(memIO.req.bits.write, memIO.req.fire)
  val reqWidth = RegEnable(memIO.req.bits.reqWidth, memIO.req.fire)
  // Convert Vec of bytes to UInt: byte0 in bits [7:0], byte1 in [15:8], etc.
  // Don't reverse - Cat puts the first element in MSBs, so we want bytes in order
  val reqData = RegEnable(memIO.req.bits.dataWrite.asUInt, memIO.req.fire)

  // Convert MemWidth to byte select mask
  val wordBytes = xlen / 8
  val selMask = Wire(Vec(wordBytes, Bool()))
  val baseMask = MemWidth.mask(xlen)(reqWidth)
  for (i <- 0 until wordBytes) {
    selMask(i) := baseMask(i)
  }

  // Drive Wishbone signals
  io.cyc := state =/= sIdle
  io.stb := state === sActive
  io.we := reqWe
  io.addr := reqAddr
  io.dataToSlave := reqData
  io.sel := selMask

  // ============================================================================
  // State Machine
  // ============================================================================

  switch(state) {
    is(sIdle) {
      when(memIO.req.fire) {
        state := sActive
      }
    }

    is(sActive) {
      when(!io.stall) {
        // Request accepted by slave
        state := sWaitAck
      }
    }

    is(sWaitAck) {
      when(io.ack || io.err) {
        // Response received
        state := sIdle
      }
    }
  }

  // ============================================================================
  // Wishbone Response -> MemoryIO
  // ============================================================================

  // Response is valid when we get ack or err
  memIO.resp.valid := io.ack || io.err
  memIO.resp.bits.valid := io.ack && !io.err

  // Convert Wishbone read data to MemoryIO format (Vec of bytes)
  for (i <- 0 until wordBytes) {
    memIO.resp.bits.dataRead(i) := io.dataToMaster((i + 1) * 8 - 1, i * 8)
  }
}
