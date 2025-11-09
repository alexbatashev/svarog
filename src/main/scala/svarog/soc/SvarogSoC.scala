package svarog.soc

import chisel3._
// import svarog.StaticInstructionRom
import svarog.bits.{PeriphMux, UART}
import svarog.memory.BlockRamMemory
import svarog.micro.Cpu
import svarog.LoadableInstructionRom
import chisel3.util.log2Ceil

class SvarogSoC(
    config: SvarogConfig,
    // program: Seq[BigInt],
    ramInitFile: Option[String] = None
) extends Module {
  // require(
  //   program.nonEmpty,
  //   "Instruction program must contain at least one word"
  // )

  // Remove UART for now. That requires a total revamp.
  // val io = IO(new Bundle {
  //   val uart = new Bundle {
  //     val tx = Output(Bool())
  //     val rx = Input(Bool())
  //   }
  // })

  val io = IO(new Bundle {
    val rom_write_en = Input(Bool())
    val rom_write_addr = Input(UInt(log2Ceil( /*rom size*/ 16384 / 4).W))
    val rom_write_data = Input(UInt(32.W))
    val rom_write_mask = Input(UInt((config.xlen / 8).W))

    val boot_hold = Input(Bool())

    val ram_write_en = Input(Bool())
    val ram_write_addr = Input(UInt(log2Ceil(config.memSizeBytes / 4).W))
    val ram_write_data = Input(UInt(config.xlen.W))
    val ram_write_mask = Input(UInt((config.xlen / 8).W))
  })

  private val cpu = Module(
    new Cpu(
      config,
      debugTapId = Some("verilator"),
      regfileProbeId = Some("verilator"),
      resetVector = config.programEntryPoint
    )
  )
  // private val instrRom = Module(new StaticInstructionRom(config.xlen, program))
  private val instrRom = Module(
    new LoadableInstructionRom(
      config.xlen, /*rom size*/ 16384,
      baseAddr = config.programEntryPoint
    )
  )

  private val dmem = Module(
    new BlockRamMemory(
      config.xlen,
      config.memSizeBytes,
      ramInitFile,
      baseAddr = config.programEntryPoint
    )
  )

  // private val periph = Module(new PeriphMux(config.xlen))
  // private val uart = Module(
  //   new UART(config.xlen, config.clockHz, config.uartInitialBaud)
  // )

  cpu.io.icache <> instrRom.io.cpu
  cpu.io.dcache <> dmem.io

  instrRom.io.write_en := io.rom_write_en
  instrRom.io.write_addr := io.rom_write_addr
  instrRom.io.write_data := io.rom_write_data
  instrRom.io.write_mask := io.rom_write_mask
  // periph.io.cpu <> cpu.io.dcache
  // periph.io.ram <> dmem.io
  // periph.io.uart <> uart.io.bus
  cpu.io.bootHold := io.boot_hold
  dmem.preload.en := io.ram_write_en
  dmem.preload.addr := io.ram_write_addr
  dmem.preload.data := io.ram_write_data
  dmem.preload.mask := io.ram_write_mask

  // uart.io.rx := io.uart.rx
  // io.uart.tx := uart.io.tx
}
