package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO}

class FetchIO(xlen: Int) extends Bundle {
  val pc_out = Output(UInt(xlen.W))
  val instruction = Output(UInt(32.W))
  val valid = Output(Bool())

  val stall = Input(Bool()) // Stall from pipeline
  val flush = Input(Bool()) // Flush on branch/jump
  val branch_target = Input(UInt(xlen.W)) // New PC from execute stage
  val branch_taken = Input(Bool())

  val mem = new MemoryIO(xlen, xlen)
}

class Fetch(xlen: Int, resetVector: BigInt = 0) extends Module {
  val io = IO(new FetchIO(xlen))

  private val resetVec = resetVector.U(xlen.W)
  val pc_reg = RegInit(resetVec)
  val pc_out_reg = RegInit(resetVec)

  val pc_plus_4 = pc_reg + 4.U
  val next_pc = Mux(io.branch_taken, io.branch_target, pc_plus_4)

  when(!io.stall) {
    pc_out_reg := pc_reg
  }

  when(io.branch_taken || io.flush) {
    pc_reg := next_pc
  }.elsewhen(!io.stall) {
    pc_reg := pc_plus_4
  }

  io.mem.req.valid := !io.stall
  io.mem.req.bits.address := pc_reg
  io.mem.req.bits.reqWidth := 4.U
  io.mem.req.bits.write := false.B
  io.mem.req.bits.dataWrite := VecInit(Seq.fill(xlen / 8)(0.U(8.W)))
  io.mem.resp.ready := true.B

  io.pc_out := pc_out_reg

  when(io.mem.resp.valid) {
    io.instruction := io.mem.resp.bits.dataRead.asUInt
    io.valid := io.mem.resp.bits.valid && !io.flush
  }.otherwise {
    io.instruction := 0.U
    io.valid := false.B
  }
}
