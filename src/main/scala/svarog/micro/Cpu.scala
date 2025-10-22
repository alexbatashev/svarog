package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.L1CacheCpuIO

class CpuIO(xlen: Int) extends Bundle {
  val icache = Flipped(new L1CacheCpuIO(xlen, 32))
}

class Cpu(xlen: Int) extends Module {
  val io = IO(new CpuIO(xlen))

  val fetch = Module(new Fetch(xlen))
  val fetchDecodeReg = Module(new FetchDecodeStage(xlen))
  val decode = Module(new Decode(xlen))

  val stall_pipeline = Wire(Bool())
  val flush_pipeline = Wire(Bool())

  stall_pipeline := false.B
  flush_pipeline := false.B

  fetch.io.stall := stall_pipeline
  fetch.io.flush := flush_pipeline
  fetch.io.branch_target := 0.U // Will come from execute stage
  fetch.io.branch_taken := false.B // Will come from execute stage
  fetch.io.icache <> io.icache

  fetchDecodeReg.io.fetch_pc := fetch.io.pc_out
  fetchDecodeReg.io.fetch_instruction := fetch.io.instruction
  fetchDecodeReg.io.fetch_valid := fetch.io.valid
  fetchDecodeReg.io.stall := stall_pipeline
  fetchDecodeReg.io.flush := flush_pipeline

  decode.io.instruction := fetchDecodeReg.io.decode_instruction
  decode.io.cur_pc := fetchDecodeReg.io.decode_pc
  decode.io.valid := fetchDecodeReg.io.decode_valid
  decode.io.stall := stall_pipeline
}

class FetchDecodeReg(xlen: Int) extends Bundle {
  val pc = UInt(xlen.W)
  val instruction = UInt(32.W)
  val valid = Bool()
}

class FetchDecodeStage(xlen: Int) extends Module {
  val io = IO(new Bundle {
    // From Fetch stage
    val fetch_pc = Input(UInt(xlen.W))
    val fetch_instruction = Input(UInt(32.W))
    val fetch_valid = Input(Bool())

    // To Decode stage
    val decode_pc = Output(UInt(xlen.W))
    val decode_instruction = Output(UInt(32.W))
    val decode_valid = Output(Bool())

    // Pipeline control
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  val reg = RegInit(0.U.asTypeOf(new FetchDecodeReg(xlen)))

  when(io.flush) {
    reg.valid := false.B
    reg.pc := 0.U
    reg.instruction := 0.U
  }.elsewhen(!io.stall) {
    reg.pc := io.fetch_pc
    reg.instruction := io.fetch_instruction
    reg.valid := io.fetch_valid
  }

  io.decode_pc := reg.pc
  io.decode_instruction := reg.instruction
  io.decode_valid := reg.valid
}
