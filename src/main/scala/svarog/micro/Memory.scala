package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO => MemIO, MemWidth}
import svarog.decoder.OpType

class MemResult(xlen: Int) extends Bundle {
  val opType = Output(OpType())
  val rd = Output(UInt(5.W))
  val gprWrite = Output(Bool())
  val gprData = Output(UInt(xlen.W))
  val csrAddr = Output(UInt(12.W))
  val csrWrite = Output(Bool())
  val csrData = Output(UInt(xlen.W))
  val pc = Output(UInt(xlen.W))
  val storeAddr = Output(UInt(xlen.W)) // Store address for watchpoint
  val isStore = Output(Bool()) // Flag indicating if this was a store
}

class Memory(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val ex = Flipped(Decoupled(new ExecuteResult(xlen)))
    val res = Decoupled(new MemResult(xlen))
    val hazard = Valid(UInt(5.W))
    val csrHazard = Valid(new HazardUnitCSRIO)
  })

  val mem = IO(new MemIO(xlen, xlen))

  mem.req.valid := false.B

  val wordSize = xlen / 8
  val offsetWidth = log2Ceil(wordSize)

  // Split store data into bytes
  val storeDataBytes = Wire(Vec(wordSize, UInt(8.W)))
  for (i <- 0 until wordSize) {
    storeDataBytes(i) := io.ex.bits.storeData(8 * (i + 1) - 1, 8 * i)
  }

  // Default values
  mem.req.bits.dataWrite := VecInit(Seq.fill(wordSize)(0.U(8.W)))
  mem.req.bits.write := false.B
  mem.req.bits.mask := VecInit(Seq.fill(wordSize)(false.B))
  mem.resp.ready := true.B
  mem.req.bits.address := 0.U

  val pendingLoad = RegInit(false.B)
  val pendingRd = RegInit(0.U(5.W))
  val pendingPC = RegInit(0.U(xlen.W))
  val pendingRegWrite = RegInit(false.B)
  val pendingUnsigned = RegInit(false.B)
  val pendingWidth = RegInit(MemWidth.WORD)
  val pendingAddress = RegInit(0.U(xlen.W))
  val pendingWordOffset = RegInit(0.U(offsetWidth.W))

  io.ex.ready := !pendingLoad
  io.hazard.valid := false.B
  io.hazard.bits := 0.U
  io.csrHazard.valid := false.B
  io.csrHazard.bits.addr := 0.U
  io.csrHazard.bits.isWrite := false.B

  when(pendingLoad) {
    io.hazard.valid := pendingRd =/= 0.U
    io.hazard.bits := pendingRd
  }.elsewhen(io.ex.valid && io.ex.bits.gprWrite && io.ex.bits.rd =/= 0.U) {
    io.hazard.valid := true.B
    io.hazard.bits := io.ex.bits.rd
  }

  when(io.ex.valid && io.ex.bits.csrWrite) {
    io.csrHazard.valid := true.B
    io.csrHazard.bits.addr := io.ex.bits.csrAddr
    io.csrHazard.bits.isWrite := true.B
  }

  val wbOpType = Wire(OpType())
  val wbRd = Wire(UInt(5.W))
  val wbRegWrite = Wire(Bool())
  val wbResult = Wire(UInt(xlen.W))
  val wbCsrAddr = Wire(UInt(12.W))
  val wbCsrWrite = Wire(Bool())
  val wbCsrData = Wire(UInt(xlen.W))
  val resValid = WireDefault(false.B)
  val wbPC = Wire(UInt(xlen.W))
  val wbStoreAddr = Wire(UInt(xlen.W))
  val wbIsStore = Wire(Bool())

  wbOpType := io.ex.bits.opType
  wbRd := io.ex.bits.rd
  wbRegWrite := io.ex.bits.gprWrite
  wbResult := io.ex.bits.gprResult
  wbCsrAddr := io.ex.bits.csrAddr
  wbCsrWrite := io.ex.bits.csrWrite
  wbCsrData := io.ex.bits.csrResult
  wbPC := io.ex.bits.pc
  wbStoreAddr := 0.U
  wbIsStore := false.B

  def extractData(
      bytes: Vec[UInt],
      width: MemWidth.Type,
      unsigned: Bool,
      wordOffset: UInt
  ): UInt = {
    val extracted = Wire(UInt(xlen.W))
    extracted := 0.U

    // Shift read data based on byte offset
    val shiftedBytes = Wire(Vec(wordSize, UInt(8.W)))
    for (j <- 0 until wordSize) {
      val readValue = Wire(UInt(8.W))
      readValue := 0.U
      for (offset <- 0 until wordSize) {
        when(wordOffset === offset.U) {
          val targetIdx = (offset + j) % wordSize
          readValue := bytes(targetIdx)
        }
      }
      shiftedBytes(j) := readValue
    }

    switch(width) {
      is(MemWidth.BYTE) {
        val byteData = shiftedBytes(0)
        extracted := Mux(
          unsigned,
          Cat(0.U((xlen - 8).W), byteData),
          Cat(Fill(xlen - 8, byteData(7)), byteData)
        )
      }
      is(MemWidth.HALF) {
        val halfData = Cat(shiftedBytes(1), shiftedBytes(0))
        extracted := Mux(
          unsigned,
          Cat(0.U((xlen - 16).W), halfData),
          Cat(Fill(xlen - 16, halfData(15)), halfData)
        )
      }
      is(MemWidth.WORD) {
        extracted := shiftedBytes.asUInt
      }
      is(MemWidth.DWORD) {
        extracted := shiftedBytes.asUInt
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
      wbResult := extractData(loadedBytes, pendingWidth, pendingUnsigned, pendingWordOffset)
      pendingLoad := false.B
      resValid := true.B
    }.otherwise {
      wbResult := 0.U
    }
  }.elsewhen(io.ex.valid) {
    // Compute word-aligned address and byte offset
    val byteAddr = io.ex.bits.memAddress
    val wordAlignedAddr = (byteAddr / wordSize.U) * wordSize.U
    val wordOffset = byteAddr(offsetWidth - 1, 0)

    mem.req.bits.address := wordAlignedAddr
    resValid := true.B
    // Generate base mask
    val baseMask = MemWidth.mask(xlen)(io.ex.bits.memWidth)

    // Shift mask based on byte offset
    val shiftedMask = Wire(Vec(wordSize, Bool()))
    for (j <- 0 until wordSize) {
      val jWide = j.U(32.W)
      val offsetWide = jWide - wordOffset
      val offset = offsetWide(offsetWidth - 1, 0)
      shiftedMask(j) := Mux(
        (j.U >= wordOffset) && (offset < baseMask.length.U),
        baseMask(offset),
        false.B
      )
    }

    // Shift write data to align with byte offset
    val shiftedWriteData = Wire(Vec(wordSize, UInt(8.W)))
    for (j <- 0 until wordSize) {
      val jWide = j.U(32.W)
      val offsetWide = jWide - wordOffset
      val offset = offsetWide(offsetWidth - 1, 0)
      shiftedWriteData(j) := Mux(
        (j.U >= wordOffset) && (offset < storeDataBytes.length.U),
        storeDataBytes(offset),
        0.U
      )
    }

    switch(io.ex.bits.opType) {
      is(OpType.LOAD) {
        mem.req.valid := true.B
        mem.req.bits.write := false.B
        mem.req.bits.mask := shiftedMask
        pendingLoad := true.B
        pendingRd := io.ex.bits.rd
        pendingPC := io.ex.bits.pc
        pendingRegWrite := io.ex.bits.gprWrite
        pendingUnsigned := io.ex.bits.memUnsigned
        pendingWidth := io.ex.bits.memWidth
        pendingAddress := io.ex.bits.memAddress
        pendingWordOffset := wordOffset
        wbOpType := OpType.NOP
        wbRegWrite := false.B
        resValid := false.B
      }

      is(OpType.STORE) {
        mem.req.valid := true.B
        mem.req.bits.write := true.B
        mem.req.bits.mask := shiftedMask
        mem.req.bits.dataWrite := shiftedWriteData
        wbRegWrite := false.B
        wbResult := 0.U
        wbStoreAddr := io.ex.bits.memAddress
        wbIsStore := true.B
      }
    }
  }

  io.res.bits.opType := wbOpType
  io.res.bits.rd := wbRd
  io.res.bits.gprWrite := wbRegWrite
  io.res.bits.gprData := wbResult
  io.res.bits.csrAddr := wbCsrAddr
  io.res.bits.csrWrite := wbCsrWrite
  io.res.bits.csrData := wbCsrData
  io.res.valid := resValid
  io.res.bits.pc := wbPC
  io.res.bits.storeAddr := wbStoreAddr
  io.res.bits.isStore := wbIsStore
}
