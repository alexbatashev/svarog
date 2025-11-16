package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO}
import svarog.decoder.InstWord
import svarog.memory.MemWidth

class FetchIO(xlen: Int) extends Bundle {
  val inst_out = Decoupled(new InstWord(xlen))

  val branch = Flipped(Valid(new BranchFeedback(xlen)))

  val mem = new MemoryIO(xlen, xlen)
}

class Fetch(xlen: Int, resetVector: BigInt = 0) extends Module {
  val io = IO(new FetchIO(xlen))

  // For now we statically predict branches as not taken, so io.branch.valid is
  // also the flush signal. In future we'll need to reconsider this decision.

  private val resetVec = resetVector.U(xlen.W)
  val pc_reg = RegInit(resetVec)
  val pc_out_reg = RegInit(resetVec)

  val pc_plus_4 = pc_reg + 4.U
  val next_pc = Mux(io.branch.valid, io.branch.bits.targetPC, pc_plus_4)

  private val pending_response = RegInit(false.B)
  private val pending_data = Reg(UInt(32.W))
  private val pending_pc = Reg(UInt(xlen.W))

  private val instruction_accepted = io.inst_out.valid && io.inst_out.ready

  when(io.branch.valid) {
    pc_reg := next_pc
    pending_response := false.B
  }.elsewhen(!io.inst_out.ready && instruction_accepted) {
    pc_reg := pc_plus_4
  }

  when(!io.inst_out.ready && !pending_response) {
    pc_out_reg := pc_reg
  }

  io.mem.req.valid := !io.inst_out.ready && !pending_response
  io.mem.req.bits.address := pc_reg
  io.mem.req.bits.reqWidth := MemWidth.WORD
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

  io.inst_out.bits.pc := Mux(pending_response, pending_pc, pc_out_reg)
  io.inst_out.bits.word := Mux(
    pending_response,
    pending_data,
    io.mem.resp.bits.dataRead.asUInt
  )

  // FIXME do we need to specially handle flush here??
  io.inst_out.valid := (pending_response || io.mem.resp.valid)
}
