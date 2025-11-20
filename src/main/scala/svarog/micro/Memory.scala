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
  val storeAddr = Output(UInt(xlen.W)) // Store address for watchpoint
  val isStore = Output(Bool()) // Flag indicating if this was a store
}

class Memory(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val ex = Flipped(Decoupled(new ExecuteResult(xlen)))
    val res = Decoupled(new MemResult(xlen))
    val hazard = Valid(UInt(5.W))
  })

  val mem = IO(new MemIO(xlen, xlen))

  mem.req.valid := false.B

  // Split store data into bytes; Memory backend applies address offset itself
  val storeDataBytes = Wire(Vec(xlen / 8, UInt(8.W)))
  for (i <- 0 until (xlen / 8)) {
    storeDataBytes(i) := io.ex.bits.storeData(8 * (i + 1) - 1, 8 * i)
  }

  mem.req.bits.dataWrite := storeDataBytes
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
  val pendingAddress = RegInit(0.U(xlen.W))

  io.ex.ready := !pendingLoad
  io.hazard.valid := false.B
  io.hazard.bits := 0.U
  when(pendingLoad) {
    io.hazard.valid := pendingRd =/= 0.U
    io.hazard.bits := pendingRd
  }.elsewhen(io.ex.valid && io.ex.bits.regWrite && io.ex.bits.rd =/= 0.U) {
    io.hazard.valid := true.B
    io.hazard.bits := io.ex.bits.rd
  }

  val wbOpType = Wire(OpType())
  val wbRd = Wire(UInt(5.W))
  val wbRegWrite = Wire(Bool())
  val wbResult = Wire(UInt(xlen.W))
  val resValid = WireDefault(false.B)
  val wbPC = Wire(UInt(xlen.W))
  val wbStoreAddr = Wire(UInt(xlen.W))
  val wbIsStore = Wire(Bool())

  wbOpType := io.ex.bits.opType
  wbRd := io.ex.bits.rd
  wbRegWrite := io.ex.bits.regWrite
  wbResult := io.ex.bits.intResult
  wbPC := io.ex.bits.pc
  wbStoreAddr := 0.U
  wbIsStore := false.B

  def extractData(
      bytes: Vec[UInt],
      width: MemWidth.Type,
      unsigned: Bool
  ): UInt = {
    val extracted = Wire(UInt(xlen.W))
    extracted := 0.U
    switch(width) {
      is(MemWidth.BYTE) {
        val byteData = bytes(0)
        extracted := Mux(
          unsigned,
          Cat(0.U((xlen - 8).W), byteData),
          Cat(Fill(xlen - 8, byteData(7)), byteData)
        )
      }
      is(MemWidth.HALF) {
        val halfData = Cat(bytes(1), bytes(0))
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
    wbOpType := OpType.LOAD
    wbRd := pendingRd
    wbPC := pendingPC
    wbRegWrite := pendingRegWrite
    wbStoreAddr := 0.U
    wbIsStore := false.B
    when(mem.resp.valid) {
      val loadedBytes = mem.resp.bits.dataRead
      wbResult := extractData(loadedBytes, pendingWidth, pendingUnsigned)
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
        wbStoreAddr := io.ex.bits.memAddress
        wbIsStore := true.B
      }
    }
  }

  io.res.bits.opType := wbOpType
  io.res.bits.rd := wbRd
  io.res.bits.regWrite := wbRegWrite
  io.res.bits.regData := wbResult
  io.res.valid := resValid
  io.res.bits.pc := wbPC
  io.res.bits.storeAddr := wbStoreAddr
  io.res.bits.isStore := wbIsStore
}
