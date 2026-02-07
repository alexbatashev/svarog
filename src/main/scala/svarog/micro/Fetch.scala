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
  val predictorUpdate = Flipped(Valid(new BranchUpdate(xlen)))

  val mem = new MemoryIO(xlen, xlen)
}

class Fetch(xlen: Int, resetVector: BigInt = 0) extends Module {
  val io = IO(new FetchIO(xlen))

  // Fetch uses a simple branch predictor. io.branch.valid is a redirect on
  // mispredict or exception/interrupt.

  private val resetVec = resetVector.U(xlen.W)
  val pc_reg = RegInit(resetVec)
  val reqPending = RegInit(false.B)
  val dropResponse = RegInit(false.B)
  val respPending = RegInit(false.B)
  val respData = Reg(UInt(32.W))
  val respPC = Reg(UInt(xlen.W))
  val respPredTaken = RegInit(false.B)
  val respPredTarget = RegInit(0.U(xlen.W))

  val predictor = Module(new BranchPredictor(xlen))
  predictor.io.queryPC := pc_reg
  predictor.io.update := io.predictorUpdate

  val pc_plus_4 = pc_reg + 4.U
  val predictedNext = Mux(
    predictor.io.predictedTaken,
    predictor.io.predictedTarget,
    pc_plus_4
  )
  val next_pc = Mux(
    io.debugSetPC.valid,
    io.debugSetPC.bits,
    Mux(io.branch.valid, io.branch.bits.targetPC, predictedNext)
  )

  val canRequest = !reqPending && !respPending && !io.halt
  io.mem.req.valid := canRequest
  io.mem.req.bits.address := pc_reg
  io.mem.req.bits.mask := MemoryUtils.fullWordMask(xlen / 8)
  io.mem.req.bits.write := false.B
  io.mem.req.bits.dataWrite := VecInit(Seq.fill(xlen / 8)(0.U(8.W)))

  when(io.mem.req.fire) {
    reqPending := true.B
    respPC := pc_reg
    respPredTaken := predictor.io.predictedTaken
    respPredTarget := predictor.io.predictedTarget
    pc_reg := predictedNext
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
  io.inst_out.bits.predictedTaken := respPredTaken
  io.inst_out.bits.predictedTarget := respPredTarget

  when(io.inst_out.fire) {
    respPending := false.B
  }

  when(io.debugSetPC.valid) {
    pc_reg := io.debugSetPC.bits
    reqPending := false.B
    respPending := false.B
    dropResponse := false.B
    respPredTaken := false.B
    respPredTarget := 0.U
  }.elsewhen(io.branch.valid) {
    pc_reg := io.branch.bits.targetPC
    respPending := false.B
    val needDrop = reqPending && !io.mem.resp.valid
    dropResponse := needDrop
    reqPending := false.B
    respPredTaken := false.B
    respPredTarget := 0.U
  }
}
