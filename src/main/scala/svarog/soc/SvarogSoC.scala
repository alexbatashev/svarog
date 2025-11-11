package svarog.soc

import chisel3._
import svarog.micro.Cpu
import svarog.memory.TCM
import chisel3.util.log2Ceil

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
      debugTapId = Some("verilator"),
      regfileProbeId = Some("verilator"),
      resetVector = config.programEntryPoint
    )
  )

  private val mem = Module(
    new TCM(
      config.xlen,
      config.memSizeBytes,
      baseAddr = config.programEntryPoint,
      debugEnabled = config.enableDebugInterface
    )
  )

  cpu.io.instmem <> mem.io.instr
  cpu.io.datamem <> mem.io.data
  cpu.io.halt := io.halt
  cpu.io.tohostAddr := io.tohostAddr

  // Connect debug interface if enabled
  mem.debug.foreach { dbg =>
    dbg.req.valid := io.mem_write_en
    dbg.req.bits.address := io.mem_write_addr
    dbg.req.bits.dataWrite := io.mem_write_data
    dbg.req.bits.write := true.B // Always write for now
    dbg.resp.ready := true.B
  }
}
