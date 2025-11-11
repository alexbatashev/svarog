package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO => MemIO}

class MemoryStageIO(xlen: Int) extends Bundle {
  // Inputs from Execute stage
  val opType = Input(OpType())
  val rd = Input(UInt(5.W))
  val regWrite = Input(Bool())
  val intResult = Input(UInt(xlen.W))
  val memAddress = Input(UInt(xlen.W))
  val memWidth = Input(MemWidth())
  val memUnsigned = Input(Bool())
  val storeData = Input(UInt(xlen.W))

  // Outputs to Writeback stage
  val wbOpType = Output(OpType())
  val wbRd = Output(UInt(5.W))
  val wbRegWrite = Output(Bool())
  val wbResult = Output(UInt(xlen.W))

  // Stall signal for multi-cycle memory operations
  val stall = Output(Bool())
}

class Memory(xlen: Int) extends Module {
  val io = IO(new MemoryStageIO(xlen))

  val mem = IO(new MemIO(xlen, xlen))

  mem.req.valid := false.B

  val byteOffset = io.memAddress(1, 0)
  val shiftAmount = Cat(byteOffset, 0.U(3.W))
  val shiftedStoreData = (io.storeData << shiftAmount)(xlen - 1, 0)
  val alignedStoreData = Wire(Vec(xlen / 8, UInt(8.W)))
  for (i <- 0 until (xlen / 8)) {
    alignedStoreData(i) := shiftedStoreData(8 * (i + 1) - 1, 8 * i)
  }

  mem.req.bits.dataWrite := alignedStoreData
  mem.req.bits.write := false.B
  mem.req.bits.reqWidth := io.memWidth.asUInt
  mem.resp.ready := true.B

  val pendingLoad = RegInit(false.B)
  val pendingRd = RegInit(0.U(5.W))
  val pendingRegWrite = RegInit(false.B)
  val pendingUnsigned = RegInit(false.B)
  val pendingWidth = RegInit(MemWidth.BYTE)
  val pendingByteOffset = RegInit(0.U(2.W))
  val pendingAddress = RegInit(0.U(xlen.W))

  val wbOpType = Wire(OpType())
  val wbRd = Wire(UInt(5.W))
  val wbRegWrite = Wire(Bool())
  val wbResult = Wire(UInt(xlen.W))
  val stallSignal = WireDefault(false.B)

  wbOpType := io.opType
  wbRd := io.rd
  wbRegWrite := io.regWrite
  wbResult := io.intResult

  def extractData(
      bytes: Vec[UInt],
      width: MemWidth.Type,
      unsigned: Bool,
      byteOffset: UInt
  ): UInt = {
    val extracted = Wire(UInt(xlen.W))
    extracted := 0.U
    switch(width) {
      is(MemWidth.BYTE) {
        val byteData = bytes(byteOffset)
        extracted := Mux(
          unsigned,
          Cat(0.U((xlen - 8).W), byteData),
          Cat(Fill(xlen - 8, byteData(7)), byteData)
        )
      }
      is(MemWidth.HALF) {
        val halfData = Cat(bytes(byteOffset + 1.U), bytes(byteOffset))
        extracted := Mux(
          unsigned,
          Cat(0.U((xlen - 16).W), halfData),
          Cat(Fill(xlen - 16, halfData(15)), halfData)
        )
      }
      is(MemWidth.WORD) {
        extracted := bytes.asUInt
      }
      is(MemWidth.DWORD) {
        extracted := bytes.asUInt
      }
    }
    extracted
  }

  when(pendingLoad) {
    mem.req.bits.address := pendingAddress
    wbOpType := OpType.LOAD
    wbRd := pendingRd
    wbRegWrite := pendingRegWrite
    stallSignal := true.B
    when(mem.resp.valid && mem.resp.bits.valid) {
      val loadedBytes = mem.resp.bits.dataRead
      wbResult := extractData(loadedBytes, pendingWidth, pendingUnsigned, pendingByteOffset)
      stallSignal := false.B
      pendingLoad := false.B
    }.otherwise {
      wbResult := 0.U
    }
  }.otherwise {
    mem.req.bits.address := io.memAddress
    switch(io.opType) {
      is(OpType.LOAD) {
        mem.req.valid := true.B
        mem.req.bits.write := false.B
        pendingLoad := true.B
        pendingRd := io.rd
        pendingRegWrite := io.regWrite
        pendingUnsigned := io.memUnsigned
        pendingWidth := io.memWidth
        pendingByteOffset := io.memAddress(1, 0)
        pendingAddress := io.memAddress
        wbOpType := OpType.NOP
        wbRegWrite := false.B
        stallSignal := true.B
      }

      is(OpType.STORE) {
        mem.req.valid := true.B
        mem.req.bits.write := true.B
        wbRegWrite := false.B
        wbResult := 0.U
      }
    }
  }

  io.wbOpType := wbOpType
  io.wbRd := wbRd
  io.wbRegWrite := wbRegWrite
  io.wbResult := wbResult
  io.stall := stallSignal
}
