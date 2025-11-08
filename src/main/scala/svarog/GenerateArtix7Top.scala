package svarog

import chisel3._
import chisel3.util.log2Ceil
import circt.stage.FirtoolOption
import svarog.memory.L1CacheCpuIO
import svarog.soc.SvarogSoC
import svarog.util.HexLoader
import java.nio.file.{Files, Paths}

/** Hard-wired instruction ROM that satisfies the CPU's simple L1 cache interface. */
class StaticInstructionRom(xlen: Int, program: Seq[BigInt]) extends Module {
  require(program.nonEmpty, "Program must contain at least one instruction")

  val io = IO(new Bundle {
    val cpu = new L1CacheCpuIO(xlen, 32)
  })

  private val romWords = program.map(word => word.U(32.W))
  private val rom = VecInit(romWords)
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
class Artix7Top(
  xlen: Int = 32,
  dataMemBytes: Int = 4096,
  clockHz: Int = 50_000_000,
  baud: Int = 115200
) extends RawModule {
  val clock = IO(Input(Clock()))
  val reset_n = IO(Input(Bool())) // active-low manual reset
  val uart_tx = IO(Output(Bool()))
  val uart_rx = IO(Input(Bool()))

  withClockAndReset(clock, !reset_n) {
    val soc = Module(
      new SvarogSoC(
        xlen = xlen,
        memBytes = dataMemBytes,
        clockHz = clockHz,
        baud = baud,
        program = Artix7Top.programWords
      )
    )

    soc.io.uart.rx := uart_rx
    uart_tx := soc.io.uart.tx
  }
}

object Artix7Top {
  private val defaultProgram: Seq[BigInt] = Seq(
    "00100093",
    "00200113",
    "002081B3",
    "00302023",
    "FF1FF06F"
  ).map(hex => BigInt(hex, 16))

  private val bootImagePath = "firmware/build/uart_loader.hex"

  def programWords: Seq[BigInt] = {
    val path = Paths.get(bootImagePath)
    if (Files.exists(path)) {
      println(s"[info] Loading boot ROM from $bootImagePath")
      HexLoader.loadWords(bootImagePath)
    } else {
      println(s"[warn] Boot ROM $bootImagePath not found, using default stub program.")
      defaultProgram
    }
  }
}

object GenerateArtix7Top extends App {
  emitVerilog(
    new Artix7Top(),
    Array("--target-dir", "generated/artix7"),
    Seq(FirtoolOption("--default-layer-specialization=disable"))
  )
}
