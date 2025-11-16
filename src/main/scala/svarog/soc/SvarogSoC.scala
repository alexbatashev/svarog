package svarog.soc

import chisel3._
import svarog.micro.Cpu
import svarog.memory.TCM
import chisel3.util.log2Ceil
import svarog.debug.ChipDebugModule

class SvarogSoC(
    config: SvarogConfig,
    ramInitFile: Option[String] = None
) extends Module {

  val io = IO(new Bundle {
    val mem_write_en = Input(Bool())
    val mem_write_addr = Input(UInt(config.xlen.W))
    val mem_write_data = Input(UInt(8.W))

    val halt = Input(Bool())
    val tohostAddr = Input(UInt(config.xlen.W))
  })

  private val cpu = Module(
    new Cpu(
      config,
      regfileProbeId = Some("verilator"),
      resetVector = config.programEntryPoint
    )
  )

  private val mem = Module(
    new TCM(
      config.xlen,
      config.memSizeBytes,
      baseAddr = config.programEntryPoint,
      numPorts = 2
    )
  )

  // private val debug = Module(new ChipDebugModule(config.xlen, numHarts = 1))

  cpu.io.instmem <> mem.io.ports(0)
  cpu.io.datamem <> mem.io.ports(1)
  // debug.io.dmem_iface <> mem.io.ports(2)
  // debug.io.imem_iface <> mem.io.ports(3)
}
