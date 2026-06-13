// Formal top for BTOR2 model checking the Svarog core against the TMDL model.
//
// STATUS: reference wrapper. It depends on the retirement-tap patch described
// below and has NOT been elaborated in CI yet — run `./mill` to compile and
// adjust the diplomacy/memory wiring to your config before use.
//
// Idea (riscv-formal style): instantiate the core, leave instruction and data
// memory responses as free top-level inputs so bounded model checking explores
// every fetched word, tie off interrupts and debug, and surface the retirement
// signals as flat outputs named to match TIR's checker contract. Yosys
// `write_btor2` then emits one `output` line per signal, which TIR's
// `verify-btor2` stitcher wires into the checker by name.
//
// Required core patch to produce the retirement report (mechanical, not yet
// applied):
//   1. MicroOp:       add `val insn = Output(UInt(32.W))`; in SimpleDecoder set
//                     `io.decoded.bits.insn := io.inst.bits.word` after the
//                     bulk connects (and drive `.insn` in each sub-decoder's
//                     MicroOp, or default it in `MicroOp.getInvalid`).
//   2. ExecuteResult: add `insn`, `rs1Val`, `rs2Val`, `nextPc`; in Execute
//                     drive `insn := activeUop.insn`,
//                     `rs1Val := io.regFile.readData1`,
//                     `rs2Val := io.regFile.readData2`, and
//                     `nextPc := Mux(io.branch.valid, io.branch.bits.targetPC,
//                                    activeUop.pc + 4.U)`.
//   3. MemResult:     add the same four fields; in Memory thread them from
//                     `io.ex.bits` on every `io.res.bits` path.
//   4. Cpu/Writeback: add an `rvfi: RvfiPort` output to `CpuIO`; in Writeback
//                     drive it from `io.in.bits` with `valid := io.in.valid`.

package svarog.formal

import chisel3._
import svarog.config.SoC
import svarog.micro.Cpu
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule

class FormalTop(config: SoC)(implicit p: Parameters) extends Module {
  private val xlen = config.isa.xlen

  // Flat outputs named exactly as the checker's retirement inputs.
  val insn = IO(Output(UInt(32.W)))
  val pc = IO(Output(UInt(xlen.W)))
  val rs1_val = IO(Output(UInt(xlen.W)))
  val rs2_val = IO(Output(UInt(xlen.W)))
  val rd_addr = IO(Output(UInt(5.W)))
  val rd_we = IO(Output(Bool()))
  val rd_val = IO(Output(UInt(xlen.W)))
  val next_pc = IO(Output(UInt(xlen.W)))
  val valid = IO(Output(Bool()))

  val cpu = Module(LazyModule(new Cpu(config, hartId = 0)).module)

  // Abstract memory: expose the core's memory ports at the boundary so the
  // responses become free inputs (any instruction word can be fetched) and the
  // requests become observable outputs.
  val instMem = IO(chiselTypeOf(cpu.io.instMem))
  val dataMem = IO(chiselTypeOf(cpu.io.dataMem))
  instMem <> cpu.io.instMem
  dataMem <> cpu.io.dataMem

  // No external interrupts in the base model; debug is unused.
  cpu.io.timerInterrupt := false.B
  cpu.io.softwareInterrupt := false.B
  cpu.io.debug := DontCare

  // Retirement report (requires the core patch above).
  insn := cpu.io.rvfi.insn
  pc := cpu.io.rvfi.pc
  rs1_val := cpu.io.rvfi.rs1Val
  rs2_val := cpu.io.rvfi.rs2Val
  rd_addr := cpu.io.rvfi.rdAddr
  rd_we := cpu.io.rvfi.rdWe
  rd_val := cpu.io.rvfi.rdVal
  next_pc := cpu.io.rvfi.nextPc
  valid := cpu.io.rvfi.valid
}
