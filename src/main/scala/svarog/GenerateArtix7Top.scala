package svarog

import chisel3._
import chisel3.util.log2Ceil
import circt.stage.FirtoolOption
import svarog.memory.{BlockRamMemory, L1CacheCpuIO}
import svarog.micro.Cpu

/** Hard-wired instruction ROM that satisfies the CPU's simple L1 cache interface. */
class StaticInstructionRom(xlen: Int, program: Seq[UInt]) extends Module {
  require(program.nonEmpty, "Program must contain at least one instruction")

  val io = IO(new Bundle {
    val cpu = new L1CacheCpuIO(xlen, 32)
  })

  private val rom = VecInit(program)
  private val depth = rom.length
  private val pcWord = io.cpu.addr >> 2
  private val inRange = pcWord < depth.U
  private val readData =
    if (program.length == 1) rom.head
    else {
      val idx = pcWord(log2Ceil(depth) - 1, 0)
      rom(idx)
    }

  io.cpu.respValid := io.cpu.reqValid
  io.cpu.data := Mux(inRange, readData, 0.U)
}

/** Minimal FPGA-friendly top for the Artix-7 board setup. */
class Artix7Top(xlen: Int = 32, dataMemBytes: Int = 4096) extends RawModule {
  val clock = IO(Input(Clock()))
  val reset_n = IO(Input(Bool())) // active-low manual reset
  val leds = IO(Output(UInt(2.W)))
  val debug_pc = IO(Output(UInt(4.W)))
  val debug_regWrite = IO(Output(Bool()))
  val debug_writeAddr = IO(Output(UInt(5.W)))

  withClockAndReset(clock, !reset_n) {
    val cpu = Module(new Cpu(xlen))
    val instrRom = Module(new StaticInstructionRom(xlen, Artix7Top.programWords))
    val dataMem = Module(new BlockRamMemory(xlen, dataMemBytes, initFile = None))

    cpu.io.icache <> instrRom.io.cpu
    dataMem.io <> cpu.io.dcache

    // Drive simple observable signals so the design is kept during elaboration.
    val ledReg = RegInit(0.U(2.W))
    when(cpu.io.debug_regWrite) {
      ledReg := cpu.io.debug_writeData(1, 0)
    }

    leds := ledReg
    debug_pc := cpu.io.debug_pc(3, 0)
    debug_regWrite := cpu.io.debug_regWrite
    debug_writeAddr := cpu.io.debug_writeAddr
  }
}

object Artix7Top {
  /** Five RV32I instructions: 2 immediate loads, an add, a store, and a self loop. */
  def programWords: Seq[UInt] = Seq(
    "h00100093".U(32.W), // addi x1, x0, 1
    "h00200113".U(32.W), // addi x2, x0, 2
    "h002081B3".U(32.W), // add x3, x1, x2
    "h00302023".U(32.W), // sw x3, 0(x0)
    "hFF1FF06F".U(32.W)  // jal x0, -16 (loop back to first instruction)
  )
}

object GenerateArtix7Top extends App {
  emitVerilog(
    new Artix7Top(),
    Array("--target-dir", "generated/artix7"),
    Seq(FirtoolOption("--default-layer-specialization=disable"))
  )
}
