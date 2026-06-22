package svarog.dsp

import chisel3._
import chisel3.util._

/** One lane's pair of read ports into the DSP register file. */
class DspReadPort(p: DspParams) extends Bundle {
  val rs1Addr = Input(UInt(p.regBits.W))
  val rs2Addr = Input(UInt(p.regBits.W))
  val rs1Data = Output(UInt(p.xlen.W))
  val rs2Data = Output(UInt(p.xlen.W))
}

/** One lane's write-back port. */
class DspWritePort(p: DspParams) extends Bundle {
  val en = Bool()
  val addr = UInt(p.regBits.W)
  val data = UInt(p.xlen.W)
}

/** Multi-ported DSP register file.
  *
  * Reads are combinational; writes commit on the clock edge. Because every lane
  * reads before any write lands, a whole bundle observes the register state as
  * it was at the start of the cycle (classic VLIW read-before-write semantics)
  * \- the compiler/scheduler is responsible for avoiding intra-bundle
  * dependencies.
  *
  * When two lanes target the same destination in one bundle the higher-numbered
  * lane wins. A host port (driven by the main core) can preload/inspect
  * registers and takes priority over lane writes.
  */
class DspRegFile(p: DspParams) extends Module {
  val io = IO(new Bundle {
    val read = Vec(p.numLanes, new DspReadPort(p))
    val write = Input(Vec(p.numLanes, new DspWritePort(p)))

    // Host (main core) access for loading operands and reading results back.
    val hostWriteEn = Input(Bool())
    val hostWriteAddr = Input(UInt(p.regBits.W))
    val hostWriteData = Input(UInt(p.xlen.W))
    val hostReadAddr = Input(UInt(p.regBits.W))
    val hostReadData = Output(UInt(p.xlen.W))
  })

  val regs = RegInit(VecInit(Seq.fill(p.numRegs)(0.U(p.xlen.W))))

  // Combinational reads for every lane.
  io.read.foreach { port =>
    port.rs1Data := regs(port.rs1Addr)
    port.rs2Data := regs(port.rs2Addr)
  }
  io.hostReadData := regs(io.hostReadAddr)

  // Resolve writes per register. Lane writes are applied in ascending order so
  // the highest lane wins; the host write is applied last and so has the final
  // say.
  for (r <- 0 until p.numRegs) {
    val next = WireDefault(regs(r))
    for (lane <- 0 until p.numLanes) {
      when(io.write(lane).en && io.write(lane).addr === r.U) {
        next := io.write(lane).data
      }
    }
    when(io.hostWriteEn && io.hostWriteAddr === r.U) {
      next := io.hostWriteData
    }
    regs(r) := next
  }
}
