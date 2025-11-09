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

  private val regfileData = Wire(UInt(xlen.W))
  BoringUtils.addSource(regfile_read_addr, RegFileProbe.addr(regfileProbeId))
  BoringUtils.addSource(regfile_read_en, RegFileProbe.en(regfileProbeId))
  BoringUtils.addSink(regfileData, RegFileProbe.data(regfileProbeId))
  regfile_read_data := regfileData
}
