package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO}
import svarog.decoder.InstWord

class FetchIO(xlen: Int) extends Bundle {
  val inst_out = Decoupled(Vec(1, new InstWord(xlen)))

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

  private val pending_response = RegInit(false.B)
  private val pending_data = Reg(UInt(32.W))
  private val pending_pc = Reg(UInt(xlen.W))

  private val instruction_accepted = io.inst_out.valid && io.inst_out.ready

  when(io.branch_taken || io.flush) {
    pc_reg := next_pc
    pending_response := false.B
  }.elsewhen(!io.stall && instruction_accepted) {
    pc_reg := pc_plus_4
  }

  when(!io.stall && !pending_response) {
    pc_out_reg := pc_reg
  }

  io.mem.req.valid := !io.stall && !pending_response
  io.mem.req.bits.address := pc_reg
  io.mem.req.bits.reqWidth := 4.U
  io.mem.req.bits.write := false.B
  io.mem.req.bits.dataWrite := VecInit(Seq.fill(xlen / 8)(0.U(8.W)))
  io.mem.resp.ready := true.B

  when(io.mem.resp.valid && !pending_response && !io.inst_out.ready) {
    // Only latch if buffer can't accept immediately
    pending_response := true.B
    pending_data := io.mem.resp.bits.dataRead.asUInt
    pending_pc := pc_out_reg
  }.elsewhen(io.inst_out.valid && io.inst_out.ready) {
    pending_response := false.B
  }

  io.inst_out.bits(0).pc := Mux(pending_response, pending_pc, pc_out_reg)
  io.inst_out.bits(0).word := Mux(
    pending_response,
    pending_data,
    io.mem.resp.bits.dataRead.asUInt
  )
  io.inst_out.valid := (pending_response || io.mem.resp.valid) && !io.flush
}
