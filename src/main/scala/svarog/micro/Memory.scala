package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO => MemIO, MemWidth}
import svarog.decoder.OpType
import svarog.bits.MemoryUtils

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

private class MemLatch(xlen: Int) extends Bundle {
  val rd = UInt(5.W)
  val pc = UInt(xlen.W)
  val storeAddr = UInt(xlen.W)
  val isStore = Bool()
  val opWidth = MemWidth.Type()
  val unsigned = Bool()
}

class Memory(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val ex = Flipped(Decoupled(new ExecuteResult(xlen)))
    val res = Decoupled(new MemResult(xlen))
    val hazard = Valid(UInt(5.W))
    val csrHazard = Valid(new HazardUnitCSRIO)
  })

  val wordSize = xlen / 8

  val mem = IO(new MemIO(xlen, xlen))

  // Default values
  mem.req.valid := false.B
  mem.req.bits.dataWrite := VecInit(Seq.fill(wordSize)(0.U(8.W)))
  mem.req.bits.write := false.B
  mem.req.bits.mask := VecInit(Seq.fill(wordSize)(false.B))
  mem.resp.ready := true.B
  mem.req.bits.address := 0.U
  io.hazard.valid := false.B
  io.hazard.bits := 0.U
  io.csrHazard.valid := false.B
  io.csrHazard.bits.addr := 0.U
  io.csrHazard.bits.isWrite := false.B

  // Pass through data
  io.res.valid := io.ex.fire && io.ex.bits.opType =/= OpType.LOAD && io.ex.bits.opType =/= OpType.STORE
  io.res.bits.opType := io.ex.bits.opType
  io.res.bits.rd := io.ex.bits.rd
  io.res.bits.gprWrite := io.ex.bits.gprWrite
  io.res.bits.gprData := io.ex.bits.gprResult
  io.res.bits.csrAddr := io.ex.bits.csrAddr
  io.res.bits.csrWrite := io.ex.bits.csrWrite
  io.res.bits.csrData := io.ex.bits.csrResult
  io.res.bits.pc := io.ex.bits.pc
  io.res.bits.storeAddr := 0.U
  io.res.bits.isStore := false.B

  private val pendingRequest = RegInit(false.B)
  private val pendingInst = RegInit(0.U.asTypeOf(new MemLatch(xlen)))

  io.ex.ready := !pendingRequest && mem.req.ready

  def latchInst() = {
    pendingRequest := true.B

    val inst = Wire(new MemLatch(xlen))
    inst.pc := io.ex.bits.pc
    inst.rd := io.ex.bits.rd
    inst.storeAddr := io.ex.bits.memAddress
    inst.isStore := io.ex.bits.opType === OpType.STORE
    inst.opWidth := io.ex.bits.memWidth
    inst.unsigned := io.ex.bits.memUnsigned

    pendingInst := inst
  }

  def sendRequest(store: Boolean) = {
    val (address, offset) =
      MemoryUtils.alignAddress(io.ex.bits.memAddress, wordSize)
    mem.req.valid := true.B
    mem.req.bits.address := address
    mem.req.bits.write := store.B

    val data = Wire(Vec(wordSize, UInt(8.W)))
    for (i <- 0 until wordSize) {
      data(i) := io.ex.bits.storeData(8 * (i + 1) - 1, 8 * i)
    }

    mem.req.bits.dataWrite := MemoryUtils.shiftWriteData(data, offset, wordSize)
    mem.req.bits.mask := MemoryUtils.generateShiftedMask(
      io.ex.bits.memWidth,
      offset,
      xlen
    )
  }

  def extractData(
      bytes: Vec[UInt],
      width: MemWidth.Type,
      unsigned: Bool,
      wordOffset: UInt
  ): UInt = {
    val extracted = Wire(UInt(xlen.W))
    extracted := 0.U

    // Unshift read data based on byte offset
    val shiftedBytes = MemoryUtils.unshiftReadData(bytes, wordOffset, wordSize)

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

  // Hazard handling
  when(pendingRequest) {
    io.hazard.valid := !pendingInst.isStore && pendingInst.rd =/= 0.U
    io.hazard.bits := pendingInst.rd
  }.elsewhen(io.ex.valid && io.ex.bits.gprWrite && io.ex.bits.rd =/= 0.U) {
    io.hazard.valid := true.B
    io.hazard.bits := io.ex.bits.rd
  }

  when(io.ex.valid && io.ex.bits.csrWrite) {
    io.csrHazard.valid := true.B
    io.csrHazard.bits.addr := io.ex.bits.csrAddr
    io.csrHazard.bits.isWrite := true.B
  }

  when(io.ex.fire) {
    switch(io.ex.bits.opType) {
      is(OpType.LOAD) {
        latchInst()
        sendRequest(false)
      }
      is(OpType.STORE) {
        latchInst()
        sendRequest(true)
      }
    }
  }

  // Writeback stage is guaranteed to take just 1 cycle.
  // Memory ops are at least 2 cycles long. We can always
  // issue result after mem op is complete.
  when(pendingRequest && mem.resp.valid) {
    pendingRequest := false.B
    io.res.valid := true.B
    io.res.bits.pc := pendingInst.pc
    io.res.bits.rd := pendingInst.rd
    io.res.bits.storeAddr := pendingInst.storeAddr
    io.res.bits.isStore := pendingInst.isStore
    io.res.bits.opType := Mux(pendingInst.isStore, OpType.STORE, OpType.LOAD)
    io.res.bits.gprWrite := !pendingInst.isStore

    val (_, offset) = MemoryUtils.alignAddress(pendingInst.storeAddr, wordSize)
    io.res.bits.gprData := extractData(
      mem.resp.bits.dataRead,
      pendingInst.opWidth,
      pendingInst.unsigned,
      offset
    )
  }
}
