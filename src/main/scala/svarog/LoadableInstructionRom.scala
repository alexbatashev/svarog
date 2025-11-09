package svarog

import chisel3._
import chisel3.util.{log2Ceil, Fill, Cat}
import svarog.memory.L1CacheCpuIO

/**
 * Loadable instruction ROM that can be initialized from Verilator testbench.
 * Includes a write port (never used in normal operation) to prevent FIRRTL optimization.
 * The testbench can write to this memory via the write port before starting simulation.
 */
class LoadableInstructionRom(
  xlen: Int,
  romSizeBytes: Int = 16384,
  baseAddr: BigInt = 0
) extends Module {
  require(romSizeBytes % 4 == 0, "ROM size must be word-aligned")
  require(romSizeBytes > 0, "ROM size must be positive")

  val io = IO(new Bundle {
    val cpu = new L1CacheCpuIO(xlen, 32)
    // Backdoor write port for testbench program loading
    val write_en = Input(Bool())
    val write_addr = Input(UInt(log2Ceil(romSizeBytes / 4).W))
    val write_data = Input(UInt(32.W))
    val write_mask = Input(UInt((xlen / 8).W))
  })

  private val numWords = romSizeBytes / 4
  private val bytesPerWord = xlen / 8

  // Create memory array with write port
  val rom = RegInit(VecInit(Seq.fill(numWords)(0.U(32.W))))

  // Write port (for testbench loading)
  when(io.write_en) {
    val maskBytes = VecInit((0 until bytesPerWord).map { i =>
      Fill(8, io.write_mask(i))
    })
    val maskBits = Cat(maskBytes.reverse)
    val merged = (io.write_data & maskBits) | (rom(io.write_addr) & (~maskBits).asUInt)
    rom(io.write_addr) := merged
  }

  private val base = baseAddr.U(xlen.W)
  private val relAddr = io.cpu.addr - base
  private val pcWord = relAddr >> 2
  private val inRange = (io.cpu.addr >= base) && (pcWord < numWords.U)
  private val readAddr = pcWord(log2Ceil(numWords) - 1, 0)

  // Synchronous read
  private val readData = RegNext(rom(readAddr), 0.U)
  private val inRangeReg = RegNext(inRange, false.B)

  io.cpu.respValid := RegNext(io.cpu.reqValid, false.B)
  io.cpu.data := Mux(inRangeReg, readData, 0.U)
}
