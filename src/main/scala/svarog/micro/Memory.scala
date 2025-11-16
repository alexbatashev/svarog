package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO => MemIO, MemWidth}
import svarog.decoder.OpType

class MemResult(xlen: Int) extends Bundle {
  val opType = Output(OpType())
  val rd = Output(UInt(5.W))
  val regWrite = Output(Bool())
  val regData = Output(UInt(xlen.W))
  val pc = Output(UInt(xlen.W))
}

class Memory(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val ex = Flipped(Decoupled(new ExecuteResult(xlen)))
    val res = Decoupled(new MemResult(xlen))
    val hazard = Valid(UInt(5.W))
  })

  val mem = IO(new MemIO(xlen, xlen))

  mem.req.valid := false.B

  val byteOffset = io.ex.bits.memAddress(1, 0)
  val shiftAmount = Cat(byteOffset, 0.U(3.W))
  val shiftedStoreData = (io.ex.bits.storeData << shiftAmount)(xlen - 1, 0)
  val alignedStoreData = Wire(Vec(xlen / 8, UInt(8.W)))
  for (i <- 0 until (xlen / 8)) {
    alignedStoreData(i) := shiftedStoreData(8 * (i + 1) - 1, 8 * i)
  }

  mem.req.bits.dataWrite := alignedStoreData
  mem.req.bits.write := false.B
  mem.req.bits.reqWidth := io.ex.bits.memWidth
  mem.resp.ready := true.B
  mem.req.bits.address := 0.U

  val pendingLoad = RegInit(false.B)
  val pendingRd = RegInit(0.U(5.W))
  val pendingPC = RegInit(0.U(xlen.W))
  val pendingRegWrite = RegInit(false.B)
  val pendingUnsigned = RegInit(false.B)
  val pendingWidth = RegInit(MemWidth.WORD)
  val pendingByteOffset = RegInit(0.U(2.W))
  val pendingAddress = RegInit(0.U(xlen.W))

  io.ex.ready := !pendingLoad
  io.hazard.valid := io.ex.valid && !pendingLoad
  io.hazard.bits := io.ex.bits.rd

  val wbOpType = Wire(OpType())
  val wbRd = Wire(UInt(5.W))
  val wbRegWrite = Wire(Bool())
  val wbResult = Wire(UInt(xlen.W))
  val resValid = WireDefault(false.B)
  val wbPC = Wire(UInt(xlen.W))

  wbOpType := io.ex.bits.opType
  wbRd := io.ex.bits.rd
  wbRegWrite := io.ex.bits.regWrite
  wbResult := io.ex.bits.intResult
  wbPC := io.ex.bits.pc

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
    mem.req.valid := true.B
    mem.req.bits.write := false.B
    mem.req.bits.address := pendingAddress
    wbOpType := OpType.LOAD
    wbRd := pendingRd
    wbPC := pendingPC
    wbRegWrite := pendingRegWrite
    when(mem.resp.valid && mem.resp.bits.valid) {
      val loadedBytes = mem.resp.bits.dataRead
      wbResult := extractData(
        loadedBytes,
        pendingWidth,
        pendingUnsigned,
        pendingByteOffset
      )
      pendingLoad := false.B
      resValid := true.B
    }.otherwise {
      wbResult := 0.U
    }
  }.elsewhen(io.ex.valid) {
    mem.req.bits.address := io.ex.bits.memAddress
    resValid := true.B
    switch(io.ex.bits.opType) {
      is(OpType.LOAD) {
        mem.req.valid := true.B
        mem.req.bits.write := false.B
        pendingLoad := true.B
        pendingRd := io.ex.bits.rd
        pendingPC := io.ex.bits.pc
        pendingRegWrite := io.ex.bits.regWrite
        pendingUnsigned := io.ex.bits.memUnsigned
        pendingWidth := io.ex.bits.memWidth
        pendingByteOffset := io.ex.bits.memAddress(1, 0)
        pendingAddress := io.ex.bits.memAddress
        wbOpType := OpType.NOP
        wbRegWrite := false.B
        resValid := false.B
      }

      is(OpType.STORE) {
        mem.req.valid := true.B
        mem.req.bits.write := true.B
        wbRegWrite := false.B
        wbResult := 0.U
      }
    }
  }

  io.res.bits.opType := wbOpType
  io.res.bits.rd := wbRd
  io.res.bits.regWrite := wbRegWrite
  io.res.bits.regData := wbResult
  io.res.valid := resValid
  io.res.bits.pc := wbPC
}
