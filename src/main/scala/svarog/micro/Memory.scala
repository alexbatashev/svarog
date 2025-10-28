package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{DataCacheIO, DataCacheReq, DataCacheResp}

class MemoryIO(xlen: Int) extends Bundle {
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
  val io = IO(new MemoryIO(xlen))

  val dcache = IO(new DataCacheIO(xlen))

  dcache.req.valid := false.B
  dcache.req.bits.addr := io.memAddress
  dcache.req.bits.data := io.storeData
  dcache.req.bits.write := false.B
  dcache.req.bits.memWidth := io.memWidth
  dcache.req.bits.unsigned := io.memUnsigned
  dcache.resp.ready := false.B

  val loadPending = RegInit(false.B)

  io.wbOpType := io.opType
  io.wbRd := io.rd
  io.wbRegWrite := io.regWrite

  val wbResult = WireDefault(io.intResult)
  io.wbResult := wbResult

  val stallSignal = WireDefault(false.B)
  io.stall := stallSignal

  dcache.resp.ready := true.B

  switch(io.opType) {
    is(OpType.LOAD) {
      dcache.req.valid := true.B
      dcache.req.bits.write := false.B

      when(dcache.resp.valid) {
        wbResult := dcache.resp.bits.data
        loadPending := false.B
        stallSignal := false.B
      }.otherwise {
        stallSignal := true.B
        loadPending := true.B
      }
    }

    is(OpType.STORE) {
      dcache.req.valid := true.B
      dcache.req.bits.write := true.B

      io.wbRegWrite := false.B
      wbResult := 0.U
      loadPending := false.B
    }
  }

  when(loadPending) {
    when(dcache.resp.valid) {
      wbResult := dcache.resp.bits.data
      loadPending := false.B
      stallSignal := false.B
    }.otherwise {
      stallSignal := true.B
    }
  }
}
