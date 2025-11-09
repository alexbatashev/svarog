package svarog.sim

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.experimental.BoringUtils
import svarog.bits.RegFileProbe

/**
 * Verilator-friendly SoC wrapper with loadable instruction ROM.
 * Programs can be loaded from the testbench without regenerating Verilog.
 */
class VerilatorTop(
  xlen: Int = 32,
  romSizeBytes: Int = 16384,
  ramSizeBytes: Int = 16384,
  clockHz: Int = 50_000_000,
  baud: Int = 115200
) extends Module {
  private val regfileProbeId = "verilator"

  val uart_tx = IO(Output(Bool()))
  val uart_rx = IO(Input(Bool()))

  val rom_write_en = IO(Input(Bool()))
  val rom_write_addr = IO(Input(UInt(log2Ceil(romSizeBytes / 4).W)))
  val rom_write_data = IO(Input(UInt(32.W)))
  val rom_write_mask = IO(Input(UInt((xlen / 8).W)))
  val boot_hold = IO(Input(Bool()))

  val ram_write_en = IO(Input(Bool()))
  val ram_write_addr = IO(Input(UInt(log2Ceil(ramSizeBytes / 4).W)))
  val ram_write_data = IO(Input(UInt(xlen.W)))
  val ram_write_mask = IO(Input(UInt((xlen / 8).W)))

  val debug_regWrite = IO(Output(Bool()))
  val debug_writeAddr = IO(Output(UInt(5.W)))
  val debug_writeData = IO(Output(UInt(xlen.W)))
  val debug_pc = IO(Output(UInt(xlen.W)))
  val debug_branchValid = IO(Output(Bool()))
  val debug_branchRs1 = IO(Output(UInt(xlen.W)))
  val debug_branchRs2 = IO(Output(UInt(xlen.W)))
  val debug_branchTaken = IO(Output(Bool()))
  val debug_branchPc = IO(Output(UInt(xlen.W)))
  val watch_decode_hit = IO(Output(Bool()))
  val watch_decode_rd = IO(Output(UInt(5.W)))
  val watch_decode_regWrite = IO(Output(Bool()))
  val watch_decode_opType = IO(Output(UInt(xlen.W)))
  val watch_decode_imm = IO(Output(UInt(xlen.W)))
  val watch_decode_valid = IO(Output(Bool()))
  val watch_decode_reqValid = IO(Output(Bool()))
  val watch_decode_respValid = IO(Output(Bool()))
  val watch_frontend_stall = IO(Output(Bool()))
  val watch_execute_hit = IO(Output(Bool()))
  val watch_execute_rd = IO(Output(UInt(5.W)))
  val watch_execute_regWrite = IO(Output(Bool()))
  val watch_execute_opType = IO(Output(UInt(xlen.W)))
  val watch_execute_intResult = IO(Output(UInt(xlen.W)))
  val watch_execute_valid = IO(Output(Bool()))
  val watch_memory_hit = IO(Output(Bool()))
  val watch_memory_rd = IO(Output(UInt(5.W)))
  val watch_memory_regWrite = IO(Output(Bool()))
  val watch_memory_intResult = IO(Output(UInt(xlen.W)))
  val watch_writeback_hit = IO(Output(Bool()))
  val watch_writeback_rd = IO(Output(UInt(5.W)))
  val watch_writeback_regWrite = IO(Output(Bool()))
  val watch_writeback_result = IO(Output(UInt(xlen.W)))

  val regfile_read_en = IO(Input(Bool()))
  val regfile_read_addr = IO(Input(UInt(5.W)))
  val regfile_read_data = IO(Output(UInt(xlen.W)))

  private val soc = Module(
    new VerilatorSoC(
      xlen = xlen,
      romSizeBytes = romSizeBytes,
      ramSizeBytes = ramSizeBytes,
      clockHz = clockHz,
      baud = baud,
      regfileProbeId = Some(regfileProbeId)
    )
  )

  soc.io.uart.rx := uart_rx
  uart_tx := soc.io.uart.tx

  soc.io.rom_write_en := rom_write_en
  soc.io.rom_write_addr := rom_write_addr
  soc.io.rom_write_data := rom_write_data
  soc.io.rom_write_mask := rom_write_mask
  soc.io.boot_hold := boot_hold
  soc.io.ram_write_en := ram_write_en
  soc.io.ram_write_addr := ram_write_addr
  soc.io.ram_write_data := ram_write_data
  soc.io.ram_write_mask := ram_write_mask
  debug_regWrite := soc.io.debug_regWrite
  debug_writeAddr := soc.io.debug_writeAddr
  debug_writeData := soc.io.debug_writeData
  debug_pc := soc.io.debug_pc
  debug_branchValid := soc.io.debug_branchValid
  debug_branchRs1 := soc.io.debug_branchRs1
  debug_branchRs2 := soc.io.debug_branchRs2
  debug_branchTaken := soc.io.debug_branchTaken
  debug_branchPc := soc.io.debug_branchPc
  watch_decode_hit := soc.io.watch_decode_hit
  watch_decode_rd := soc.io.watch_decode_rd
  watch_decode_regWrite := soc.io.watch_decode_regWrite
  watch_decode_opType := soc.io.watch_decode_opType
  watch_decode_imm := soc.io.watch_decode_imm
  watch_decode_valid := soc.io.watch_decode_valid
  watch_decode_reqValid := soc.io.watch_decode_reqValid
  watch_decode_respValid := soc.io.watch_decode_respValid
  watch_frontend_stall := soc.io.watch_frontend_stall
  watch_execute_hit := soc.io.watch_execute_hit
  watch_execute_rd := soc.io.watch_execute_rd
  watch_execute_regWrite := soc.io.watch_execute_regWrite
  watch_execute_opType := soc.io.watch_execute_opType
  watch_execute_intResult := soc.io.watch_execute_intResult
  watch_execute_valid := soc.io.watch_execute_valid
  watch_memory_hit := soc.io.watch_memory_hit
  watch_memory_rd := soc.io.watch_memory_rd
  watch_memory_regWrite := soc.io.watch_memory_regWrite
  watch_memory_intResult := soc.io.watch_memory_intResult
  watch_writeback_hit := soc.io.watch_writeback_hit
  watch_writeback_rd := soc.io.watch_writeback_rd
  watch_writeback_regWrite := soc.io.watch_writeback_regWrite
  watch_writeback_result := soc.io.watch_writeback_result

  private val regfileData = Wire(UInt(xlen.W))
  BoringUtils.addSource(regfile_read_addr, RegFileProbe.addr(regfileProbeId))
  BoringUtils.addSource(regfile_read_en, RegFileProbe.en(regfileProbeId))
  BoringUtils.addSink(regfileData, RegFileProbe.data(regfileProbeId))
  regfile_read_data := regfileData
}
