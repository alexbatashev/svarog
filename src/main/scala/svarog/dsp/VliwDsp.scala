package svarog.dsp

import chisel3._
import chisel3.util._

/** VLIW DSP coprocessor prototype.
  *
  * Accepts one `bundleBits`-wide instruction word per cycle and executes all
  * lanes in parallel against an internal DSP register file. Each lane performs
  * `rd = rs1 (op) rs2` where op is ADD, SUB or MUL (MUL keeps the low `xlen`
  * bits, matching how the core's MUL works). NOP lanes leave their destination
  * untouched.
  *
  * This is deliberately a single-cycle, fully-combinational execute: the whole
  * point of the prototype is to show issue-width parallelism, not deep
  * pipelines. `io.inst.ready` is therefore always high.
  *
  * The block is standalone and not wired into the scalar pipeline. See
  * docs/dsp/vliw-dsp.md for the encoding and integration notes.
  */
class VliwDsp(p: DspParams = DspParams()) extends Module {
  val io = IO(new Bundle {
    // The very long instruction word. Always ready (single-cycle execute).
    val inst = Flipped(Decoupled(UInt(p.bundleBits.W)))

    // Host access to preload operands and read results back.
    val hostWriteEn = Input(Bool())
    val hostWriteAddr = Input(UInt(p.regBits.W))
    val hostWriteData = Input(UInt(p.xlen.W))
    val hostReadAddr = Input(UInt(p.regBits.W))
    val hostReadData = Output(UInt(p.xlen.W))
  })

  val regFile = Module(new DspRegFile(p))

  // Always accept a bundle: execution completes in the cycle it is issued.
  io.inst.ready := true.B
  val issue = io.inst.fire

  // Reinterpret the flat word as a vector of lanes. Lane 0 lives in the least
  // significant bits, so Vec index 0 == lane 0.
  val lanes = io.inst.bits.asTypeOf(Vec(p.numLanes, new DspLane))

  // Host port passthrough.
  regFile.io.hostWriteEn := io.hostWriteEn
  regFile.io.hostWriteAddr := io.hostWriteAddr
  regFile.io.hostWriteData := io.hostWriteData
  regFile.io.hostReadAddr := io.hostReadAddr
  io.hostReadData := regFile.io.hostReadData

  for (i <- 0 until p.numLanes) {
    val lane = lanes(i)

    regFile.io.read(i).rs1Addr := lane.rs1
    regFile.io.read(i).rs2Addr := lane.rs2
    val a = regFile.io.read(i).rs1Data
    val b = regFile.io.read(i).rs2Data

    val result = WireDefault(0.U(p.xlen.W))
    switch(lane.op) {
      is(DspOp.ADD.U) { result := a + b }
      is(DspOp.SUB.U) { result := a - b }
      is(DspOp.MUL.U) { result := (a * b)(p.xlen - 1, 0) }
    }

    regFile.io.write(i).addr := lane.rd
    regFile.io.write(i).data := result
    regFile.io.write(i).en := issue && (lane.op =/= DspOp.NOP.U)
  }
}
