package svarog.memory

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.soc.SvarogConfig

// Tightly Coupled Memory (TCM)
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
      addr >= baseAddr.U(xlen.W) && addr < (baseAddr.U(xlen.W) + memSizeBytes.U(xlen.W))

    val wordIdx = (addr - baseAddr.U) / wordSize.U
    val wordOffset = (addr - baseAddr.U)(offsetWidth - 1, 0)

    val enable = addrInRange && reqFire

    // Generate mask and shift it based on byte offset
    val baseMask = MemWidth.mask(xlen)(io.ports(i).req.bits.reqWidth)
    val shiftedMask = Wire(Vec(wordSize, Bool()))
    for (j <- 0 until wordSize) {
      shiftedMask(j) := Mux(
        (j.U >= wordOffset) && ((j.U - wordOffset) < baseMask.length.U),
        baseMask(j.U - wordOffset),
        false.B
      )
    }

    // Shift write data to align with byte offset
    val shiftedWriteData = Wire(Vec(wordSize, UInt(8.W)))
    for (j <- 0 until wordSize) {
      shiftedWriteData(j) := Mux(
        (j.U >= wordOffset) && ((j.U - wordOffset) < io.ports(i).req.bits.dataWrite.length.U),
        io.ports(i).req.bits.dataWrite(j.U - wordOffset),
        0.U
      )
    }

    val size = MemWidth.size(io.ports(i).req.bits.reqWidth)
    val readData = mem.readWrite(
      wordIdx,
      shiftedWriteData,
      shiftedMask,
      enable,
      io.ports(i).req.bits.write
    )

    // Register request metadata to line up with the registered resp.valid
    val respWordOffset = RegInit(0.U(offsetWidth.W))
    val respSize = RegInit(0.U(log2Ceil(wordSize + 1).W))
    when(reqFire) {
      respWordOffset := wordOffset
      respSize := MemWidth.size(io.ports(i).req.bits.reqWidth)
    }

    val respValid = RegNext(enable, false.B)
    io.ports(i).resp.valid := respValid
    io.ports(i).resp.bits.valid := respValid

    for (j <- 0 until wordSize) {
      val index = (respWordOffset + j.U)(offsetWidth - 1, 0)
      io.ports(i)
        .resp
        .bits
        .dataRead(j) := Mux(j.U < respSize, readData(index), 0.U)
    }
  }
}
