package svarog.memory

import chisel3._
import chisel3.util._

/**
 * Wishbone adapter for TCM
 *
 * Wraps a TCM module and exposes it as a Wishbone slave.
 * Handles protocol conversion between Wishbone B4 pipelined and the TCM's MemoryIO interface.
 *
 * @param xlen Data width (usually 32)
 * @param memSizeBytes Size of TCM in bytes
 * @param baseAddr Base address for this TCM in the address space
 */
class WishboneTCM(
    val xlen: Int = 32,
    val memSizeBytes: Int,
    val baseAddr: BigInt
) extends Module with WishboneSlave {

  val io = IO(Flipped(new WishboneIO(addrWidth = xlen, dataWidth = xlen)))

  // Address space for this slave
  def addrStart: BigInt = baseAddr
  def addrEnd: BigInt = baseAddr + memSizeBytes

  // Instantiate the actual TCM with a single port (Wishbone provides single access point)
  val tcm = Module(new TCM(xlen, memSizeBytes, baseAddr.toLong, numPorts = 1))

  // ============================================================================
  // Wishbone -> TCM Request Conversion
  // ============================================================================

  // TCM request is valid when we have both CYC and STB
  tcm.io.ports(0).req.valid := io.cyc && io.stb
  tcm.io.ports(0).req.bits.address := io.addr
  tcm.io.ports(0).req.bits.write := io.we

  // Convert Wishbone sel (Vec of Bool) to MemWidth
  // Count how many bytes are selected
  val selCount = PopCount(io.sel.asUInt)
  val reqWidth = WireDefault(MemWidth.WORD)
  when(selCount === 1.U) {
    reqWidth := MemWidth.BYTE
  }.elsewhen(selCount === 2.U) {
    reqWidth := MemWidth.HALF
  }.elsewhen(selCount === 4.U) {
    reqWidth := MemWidth.WORD
  }
  tcm.io.ports(0).req.bits.reqWidth := reqWidth

  // Convert Wishbone write data (single UInt) to TCM format (Vec of bytes)
  val wordBytes = xlen / 8
  for (i <- 0 until wordBytes) {
    tcm.io.ports(0).req.bits.dataWrite(i) := io.dataToSlave((i + 1) * 8 - 1, i * 8)
  }

  // ============================================================================
  // TCM Response -> Wishbone Conversion
  // ============================================================================

  // Always ready to receive responses
  tcm.io.ports(0).resp.ready := true.B

  // Track if we have an outstanding request
  val pendingReq = RegInit(false.B)
  val acceptingNewReq = io.cyc && io.stb && tcm.io.ports(0).req.ready && !pendingReq
  val completingReq = tcm.io.ports(0).resp.valid

  // Temporary debug: log first few requests to help diagnose bus issues
  val dbgPrintsLeft = RegInit(8.U(4.W))
  when(io.cyc && io.stb && dbgPrintsLeft =/= 0.U) {
    printf(p"[WB-TCM] req addr=0x${Hexadecimal(io.addr)} we=${io.we} sel=0x${Hexadecimal(io.sel.asUInt)} data=0x${Hexadecimal(io.dataToSlave)}\n")
    dbgPrintsLeft := dbgPrintsLeft - 1.U
  }

  when(acceptingNewReq && !completingReq) {
    // New request accepted, no completion this cycle
    pendingReq := true.B
  }.elsewhen(!acceptingNewReq && completingReq) {
    // Request completing, no new request this cycle
    pendingReq := false.B
  }
  // If both happen in same cycle, pendingReq stays the same

  // Stall if TCM is not ready or if we already have a pending request
  // (Wishbone pipelined allows multiple outstanding, but TCM is single-cycle)
  io.stall := !tcm.io.ports(0).req.ready || (pendingReq && !completingReq)

  // ACK when TCM response is valid
  io.ack := tcm.io.ports(0).resp.valid && tcm.io.ports(0).resp.bits.valid

  // Convert TCM read data (Vec of bytes) to Wishbone format (single UInt)
  // byte0 should be in bits [7:0], byte1 in [15:8], etc.
  // asUInt puts index 0 in LSBs, which is what we want
  io.dataToMaster := tcm.io.ports(0).resp.bits.dataRead.asUInt

  // TCM doesn't generate errors (always valid when responding)
  io.err := tcm.io.ports(0).resp.valid && !tcm.io.ports(0).resp.bits.valid
}
