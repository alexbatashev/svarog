// Formal top for BTOR2 model checking the Svarog core against TIR's TMDL model.
//
// Instantiates one core, leaves the instruction/data memory responses as free
// top-level inputs so bounded model checking explores every fetched word, ties
// off interrupts and debug, and surfaces the retirement report as flat outputs
// named to match TIR's checker contract (insn, pc, rs1_val, rs2_val, rd_addr,
// rd_we, rd_val, next_pc, valid). Yosys `write_btor2` emits one `output` line
// per signal, which TIR's `verify-btor2` stitcher wires into the checker by
// name. See TIR docs/tmdl/btor2_model_checking.md.

package svarog.formal

import chisel3._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import svarog.config.Cluster
import svarog.micro.Cpu

class FormalTop(hartId: Int, cluster: Cluster, startAddress: Long)(implicit p: Parameters)
    extends LazyModule {
  val cpu = LazyModule(new Cpu(hartId, cluster, startAddress))

  lazy val module = new LazyModuleImp(this) {
    private val xlen = cluster.isa.xlen

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

    // Protocol-correct memory with free contents: BMC explores every program
    // while the fetch unit never sees a phantom response.
    val imem = Module(new FormalMemory(xlen, xlen))
    val dmem = Module(new FormalMemory(xlen, xlen))
    imem.io <> cpu.module.io.instMem
    dmem.io <> cpu.module.io.dataMem

    cpu.module.io.timerInterrupt := false.B
    cpu.module.io.softwareInterrupt := false.B
    cpu.module.io.debug := DontCare

    val rvfi = cpu.module.io.rvfi
    insn := rvfi.insn
    pc := rvfi.pc
    rs1_val := rvfi.rs1Val
    rs2_val := rvfi.rs2Val
    rd_addr := rvfi.rdAddr
    rd_we := rvfi.rdWe
    rd_val := rvfi.rdVal
    next_pc := rvfi.nextPc
    valid := rvfi.valid
  }
}
