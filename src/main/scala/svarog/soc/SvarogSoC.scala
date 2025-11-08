package svarog.soc

import chisel3._
import svarog.StaticInstructionRom
import svarog.bits.{PeriphMux, UART}
import svarog.memory.BlockRamMemory
import svarog.micro.Cpu

class SvarogSoC(
  xlen: Int = 32,
  memBytes: Int = 16384,
  clockHz: Int = 50_000_000,
  baud: Int = 115200,
  program: Seq[BigInt]
) extends Module {
  require(program.nonEmpty, "Instruction program must contain at least one word")

  val io = IO(new Bundle {
    val uart = new Bundle {
      val tx = Output(Bool())
      val rx = Input(Bool())
    }
  })

  private val cpu = Module(new Cpu(xlen))
  private val instrRom = Module(new StaticInstructionRom(xlen, program))
  private val dmem = Module(new BlockRamMemory(xlen, memBytes))
  private val periph = Module(new PeriphMux(xlen))
  private val uart = Module(new UART(xlen, clockHz, baud))

  cpu.io.icache <> instrRom.io.cpu
  periph.io.cpu <> cpu.io.dcache
  periph.io.ram <> dmem.io
  periph.io.uart <> uart.io.bus

  uart.io.rx := io.uart.rx
  io.uart.tx := uart.io.tx
}
