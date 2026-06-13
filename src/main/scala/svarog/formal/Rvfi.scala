// Retirement interface for BTOR2 model checking against the TMDL golden model.
//
// One report per committed instruction. The field names here map to the
// flat output names the TMDL checker expects (see the table in TIR's
// docs/tmdl/btor2_model_checking.md): valid, insn, pc, rs1_val, rs2_val,
// rd_addr, rd_we, rd_val, next_pc. `FormalTop` exposes them under exactly
// those names so the `verify-btor2` stitcher can wire them by name.

package svarog.formal

import chisel3._

class RvfiPort(val xlen: Int) extends Bundle {
  val valid = Output(Bool())
  val insn = Output(UInt(32.W))
  val pc = Output(UInt(xlen.W))
  val rs1Val = Output(UInt(xlen.W))
  val rs2Val = Output(UInt(xlen.W))
  val rdAddr = Output(UInt(5.W))
  val rdWe = Output(Bool())
  val rdVal = Output(UInt(xlen.W))
  val nextPc = Output(UInt(xlen.W))
}
