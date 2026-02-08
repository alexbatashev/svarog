package svarog.micro

import chisel3._
import chisel3.util._
import svarog.decoder.InstWord
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.TransferSizes
import freechips.rocketchip.tilelink._

class FetchIO(xlen: Int) extends Bundle {
  val instOut = Decoupled(new InstWord(xlen))

  val branch = Flipped(Valid(new BranchFeedback(xlen)))
  val debugSetPC = Flipped(Valid(UInt(xlen.W))) // Debug interface to set PC
  val halt = Input(Bool()) // Stop fetching when halted
}

class Fetch(xlen: Int, resetVector: BigInt = 0)(implicit p: Parameters)
    extends LazyModule {
  private val beatBytes = xlen / 8

  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = "fetch",
            requestFifo = true,
            supportsProbe = TransferSizes(1, beatBytes),
            supportsGet = TransferSizes(1, beatBytes),
            supportsPutFull = TransferSizes(1, beatBytes),
            supportsPutPartial = TransferSizes(1, beatBytes)
          )
        )
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new FetchIO(xlen))
    val (tl, edge) = node.out(0)

    // For now we statically predict branches as not taken, so io.branch.valid is
    // also the flush signal. In future we'll need to reconsider this decision.

    private val resetVec = resetVector.U(xlen.W)
    private val size = log2Ceil(beatBytes).U
    private val sourceId = edge.client.masters.head.sourceId.start.U

    val pcReg = RegInit(resetVec)
    val reqPending = RegInit(false.B)
    val dropResponse = RegInit(false.B)
    val respPending = RegInit(false.B)
    val respData = Reg(UInt(32.W))
    val respPC = Reg(UInt(xlen.W))

    val pcPlus4 = pcReg + 4.U

    // Issue TileLink Get requests.
    // Suppress on redirect (branch/debugSetPC) to avoid a same-cycle race where
    // tl.a fires for the old PC while the redirect clears reqPending, leaving
    // the in-flight response with no one to accept it (tl.d.ready stuck low).
    // dropResponse blocks new requests until the stale in-flight response is
    // drained, which is required by TL-UL's one-outstanding-per-source rule.
    val canRequest = !reqPending && !respPending && !dropResponse && !io.halt &&
      !io.branch.valid && !io.debugSetPC.valid
    val (_, getReq) = edge.Get(sourceId, pcReg, size)
    tl.a.valid := canRequest
    tl.a.bits := getReq

    when(tl.a.fire) {
      reqPending := true.B
      respPC := pcReg
      pcReg := pcPlus4
    }

    // Accept responses — always ready when we expect one
    tl.d.ready := reqPending || dropResponse
    when(tl.d.fire) {
      when(dropResponse) {
        dropResponse := false.B
        reqPending := false.B
      }.otherwise {
        respPending := true.B
        respData := tl.d.bits.data
        reqPending := false.B
      }
    }

    // Output buffered instruction
    io.instOut.valid := respPending
    io.instOut.bits.pc := respPC
    io.instOut.bits.word := respData

    when(io.instOut.fire) {
      respPending := false.B
    }

    // Redirect handling — debugSetPC takes priority over branch
    when(io.debugSetPC.valid) {
      pcReg := io.debugSetPC.bits
      reqPending := false.B
      respPending := false.B
      dropResponse := false.B
    }.elsewhen(io.branch.valid) {
      pcReg := io.branch.bits.targetPC
      respPending := false.B
      val needDrop = reqPending && !tl.d.valid
      dropResponse := needDrop
      reqPending := false.B
    }
  }
}
