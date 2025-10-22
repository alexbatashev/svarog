package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.L1CacheCpuIO

class FetchIO(xlen: Int) extends Bundle {
  val pc_out = Output(UInt(xlen.W))
  val instruction = Output(UInt(32.W))
  val valid = Output(Bool())

  val stall = Input(Bool()) // Stall from pipeline
  val flush = Input(Bool()) // Flush on branch/jump
  val branch_target = Input(UInt(xlen.W)) // New PC from execute stage
  val branch_taken = Input(Bool())

  // I-Cache interface
  val icache = Flipped(new L1CacheCpuIO(xlen, 32))
}

class Fetch(xlen: Int) extends Module {
  val io = IO(new FetchIO(xlen))

  val pc_reg = RegInit(0.U(xlen.W))

  val pc_plus_4 = pc_reg + 4.U
  val next_pc = Mux(io.branch_taken, io.branch_target, pc_plus_4)

  when(!io.stall) {
    when(io.flush || io.branch_taken) {
      pc_reg := next_pc
    }.otherwise {
      pc_reg := pc_plus_4
    }
  }

  io.icache.reqValid := !io.stall
  io.icache.addr := pc_reg

  io.pc_out := pc_reg
  io.instruction := io.icache.data
  io.valid := io.icache.respValid && !io.flush
}
