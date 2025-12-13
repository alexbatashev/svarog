package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.soc.SvarogConfig

/// Tightly Coupled Memory (TCM)
class TCM(
    xlen: Int,
    memSizeBytes: Int,
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

  private val offsetWidth = log2Ceil(wordSize)

  for (i <- 0 until numPorts) {
    // Single cycle memory, always ready
    io.ports(i).req.ready := true.B

    val reqFire = io.ports(i).req.valid && io.ports(i).req.ready

    val addr = io.ports(i).req.bits.address
    val addrInRange =
      addr >= baseAddr
        .U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

    val wordIdx = (addr - baseAddr.U) / wordSize.U
    val wordOffset = (addr - baseAddr.U)(offsetWidth - 1, 0)

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
    io.ports(i).resp.bits.valid := respValid
    io.ports(i).resp.bits.dataRead := readData
  }
}

/// Wishbone adapter for TCM
class TCMWB(
    xlen: Int,
    memSizeBytes: Int,
    baseAddr: Long = 0
) extends Module
    with WishboneSlave {
  val io = IO(Flipped(new WishboneIO(addrWidth = xlen, dataWidth = xlen)))

  def addrStart: Long = baseAddr
  def addrEnd: Long = baseAddr + memSizeBytes

  val tcm = Module(
    new TCM(
      xlen,
      memSizeBytes = memSizeBytes,
      baseAddr = baseAddr,
      numPorts = 1
    )
  )

  tcm.io.ports(0).req.valid := io.cycleActive && io.strobe
  // Wishbone addresses are byte addresses on the bus; pass through.
  val wordBytes = xlen / 8
  val offsetWidth = log2Ceil(wordBytes)

  val byteOffset = io.addr(offsetWidth - 1, 0)
  tcm.io.ports(0).req.bits.address := io.addr
  tcm.io.ports(0).req.bits.write := io.writeEnable

  // Derive request width from sel (contiguous assumption).
  val selUInt = io.sel.asUInt
  val selAfterOffset = selUInt >> byteOffset
  val activeLen = PopCount(selAfterOffset)
  val reqWidth = Wire(MemWidth.Type())
  reqWidth := MemWidth.WORD
  when(activeLen === 1.U) { reqWidth := MemWidth.BYTE }
    .elsewhen(activeLen === 2.U) { reqWidth := MemWidth.HALF }
    .elsewhen(activeLen === 4.U && (wordBytes >= 4).B) {
      reqWidth := MemWidth.WORD
    }
    .elsewhen(activeLen === 8.U && (wordBytes >= 8).B) {
      reqWidth := MemWidth.DWORD
    }
  tcm.io.ports(0).req.bits.reqWidth := reqWidth

  // Split Wishbone write data into bytes
  val wbDataBytes = Wire(Vec(wordBytes, UInt(8.W)))
  for (i <- 0 until wordBytes) {
    wbDataBytes(i) := io.dataToSlave(8 * (i + 1) - 1, 8 * i)
  }
  tcm.io.ports(0).req.bits.dataWrite := wbDataBytes

  // TCM is single-cycle and always ready; no backpressure
  io.stall := false.B

  // Drive Wishbone response from TCM response
  val respValid = tcm.io.ports(0).resp.valid
  io.ack := respValid
  io.error := false.B

  // Pack bytes back into xlen data word (little-endian)
  val respBytes = tcm.io.ports(0).resp.bits.dataRead
  io.dataToMaster := Cat(respBytes.reverse)

  // Tie off unused ready on response channel
  tcm.io.ports(0).resp.ready := true.B

}
