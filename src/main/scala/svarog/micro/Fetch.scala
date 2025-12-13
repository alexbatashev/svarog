package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{MemoryRequest, MemoryIO}
import svarog.decoder.InstWord
import svarog.memory.MemWidth
import svarog.bits.MemoryUtils

class FetchIO(xlen: Int) extends Bundle {
  val inst_out = Decoupled(new InstWord(xlen))

  val branch = Flipped(Valid(new BranchFeedback(xlen)))
  val debugSetPC = Flipped(Valid(UInt(xlen.W))) // Debug interface to set PC
  val halt = Input(Bool()) // Stop fetching when halted

  val mem = new MemoryIO(xlen, xlen)
}

class Fetch(xlen: Int, resetVector: BigInt = 0) extends Module {
  val io = IO(new FetchIO(xlen))

  // For now we statically predict branches as not taken, so io.branch.valid is
  // also the flush signal. In future we'll need to reconsider this decision.

  private val resetVec = resetVector.U(xlen.W)
  val pc_reg = RegInit(resetVec)
  val reqPending = RegInit(false.B)
  val dropResponse = RegInit(false.B)
  val respPending = RegInit(false.B)
  val respData = Reg(UInt(32.W))
  val respPC = Reg(UInt(xlen.W))

  val pc_plus_4 = pc_reg + 4.U
  val next_pc = Mux(io.debugSetPC.valid, io.debugSetPC.bits,
                Mux(io.branch.valid, io.branch.bits.targetPC, pc_plus_4))

  val canRequest = !reqPending && !respPending && !io.halt
  io.mem.req.valid := canRequest
  io.mem.req.bits.address := pc_reg
  io.mem.req.bits.mask := MemoryUtils.fullWordMask(xlen / 8)
  io.mem.req.bits.write := false.B
  io.mem.req.bits.dataWrite := VecInit(Seq.fill(xlen / 8)(0.U(8.W)))

  when(io.mem.req.fire) {
    reqPending := true.B
    respPC := pc_reg
    pc_reg := pc_plus_4
  }

  io.mem.resp.ready := true.B
  when(io.mem.resp.valid) {
    when(dropResponse) {
      dropResponse := false.B
      reqPending := false.B
    }.otherwise {
      respPending := true.B
      respData := io.mem.resp.bits.dataRead.asUInt
      reqPending := false.B
    }
  }

  io.inst_out.valid := respPending
  io.inst_out.bits.pc := respPC
  io.inst_out.bits.word := respData

  when(io.inst_out.fire) {
    respPending := false.B
  }

  when(io.debugSetPC.valid) {
    pc_reg := io.debugSetPC.bits
    reqPending := false.B
    respPending := false.B
    dropResponse := false.B
  }.elsewhen(io.branch.valid) {
    pc_reg := io.branch.bits.targetPC
    respPending := false.B
    when(reqPending) {
      dropResponse := true.B
    }.otherwise {
      dropResponse := false.B
    }
    reqPending := false.B
  }
}
