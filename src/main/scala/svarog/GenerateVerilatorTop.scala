package svarog

import chisel3._
import circt.stage.FirtoolOption
import chisel3.util.log2Ceil
import svarog.soc.SvarogSoC
import svarog.bits.RegFileProbe
import chisel3.util.experimental.BoringUtils
import svarog.soc.SvarogConfig
import svarog.micro.CpuDebugTap

/** Emits a Verilator-friendly SystemVerilog top that includes the full SoC. */
object GenerateVerilatorTop extends App {
  private def parseArgs(args: Array[String]): Map[String, String] = {
    args.toList.flatMap { raw =>
      val trimmed = raw.trim
      if (trimmed.startsWith("--") && trimmed.contains("=")) {
        val Array(key, value) = trimmed.drop(2).split("=", 2)
        Some(key -> value)
      } else None
    }.toMap
  }

  private val cli = parseArgs(args)

  private val xlen = cli.get("xlen").map(_.toInt).getOrElse(32)
  private val romSizeBytes = cli
    .get("rom-size-bytes")
    .map(_.toInt)
    .orElse(cli.get("rom-size-kb").map(kb => kb.toInt * 1024))
    .getOrElse(16384)
  private val ramSizeBytes = cli
    .get("ram-size-bytes")
    .map(_.toInt)
    .orElse(cli.get("ram-size-kb").map(kb => kb.toInt * 1024))
    .getOrElse(16384)
  private val clockHz = cli.get("clock-hz").map(_.toInt).getOrElse(50_000_000)
  private val baud = cli.get("baud").map(_.toInt).getOrElse(115200)
  private val targetDir = cli.getOrElse("target-dir", "target/generated/")

  val config =
    SvarogConfig(xlen = xlen, memSizeBytes = ramSizeBytes, clockHz = clockHz)

  emitVerilog(
    new VerilatorTop(
      config
    ),
    Array("--target-dir", targetDir),
    Seq(
      FirtoolOption("--disable-all-randomization"),
      FirtoolOption("--default-layer-specialization=disable"),
      FirtoolOption("--lowering-options=disallowPortDeclSharing")
    )
  )

  println(
    s"Generated Verilator top with xlen=$xlen, romSizeBytes=$romSizeBytes, " +
      s"ramSizeBytes=$ramSizeBytes, clockHz=$clockHz, baud=$baud"
  )
  println(
    s"[info] Instruction ROM is loadable from testbench - programs can be loaded at runtime"
  )
}

class VerilatorTop(
    config: SvarogConfig
) extends Module {
  private val regfileProbeId = "verilator"
  private val debugTapId = "verilator"

  val rom_write_en = IO(Input(Bool()))
  val rom_write_addr = IO(Input(UInt(log2Ceil( /*rom size */ 16384 / 4).W)))
  val rom_write_data = IO(Input(UInt(32.W)))
  val rom_write_mask = IO(Input(UInt((config.xlen / 8).W)))
  val boot_hold = IO(Input(Bool()))

  val ram_write_en = IO(Input(Bool()))
  val ram_write_addr = IO(Input(UInt(log2Ceil(config.memSizeBytes / 4).W)))
  val ram_write_data = IO(Input(UInt(config.xlen.W)))
  val ram_write_mask = IO(Input(UInt((config.xlen / 8).W)))

  val debug_regWrite = IO(Output(Bool()))
  val debug_writeAddr = IO(Output(UInt(5.W)))
  val debug_writeData = IO(Output(UInt(config.xlen.W)))
  val debug_pc = IO(Output(UInt(config.xlen.W)))
  val debug_branchValid = IO(Output(Bool()))
  val debug_branchRs1 = IO(Output(UInt(config.xlen.W)))
  val debug_branchRs2 = IO(Output(UInt(config.xlen.W)))
  val debug_branchTaken = IO(Output(Bool()))
  val debug_branchPc = IO(Output(UInt(config.xlen.W)))

  val regfile_read_en = IO(Input(Bool()))
  val regfile_read_addr = IO(Input(UInt(5.W)))
  val regfile_read_data = IO(Output(UInt(config.xlen.W)))

  private val soc = Module(
    new SvarogSoC(
      config
      // regfileProbeId = Some(regfileProbeId)
    )
  )

  // soc.io.uart.rx := uart_rx
  // uart_tx := soc.io.uart.tx

  soc.io.rom_write_en := rom_write_en
  soc.io.rom_write_addr := rom_write_addr
  soc.io.rom_write_data := rom_write_data
  soc.io.rom_write_mask := rom_write_mask
  soc.io.boot_hold := boot_hold
  soc.io.ram_write_en := ram_write_en
  soc.io.ram_write_addr := ram_write_addr
  soc.io.ram_write_data := ram_write_data
  soc.io.ram_write_mask := ram_write_mask
  // debug_regWrite := soc.io.debug_regWrite
  // debug_writeAddr := soc.io.debug_writeAddr
  // debug_writeData := soc.io.debug_writeData
  // debug_pc := soc.io.debug_pc
  // debug_branchValid := soc.io.debug_branchValid
  // debug_branchRs1 := soc.io.debug_branchRs1
  // debug_branchRs2 := soc.io.debug_branchRs2
  // debug_branchTaken := soc.io.debug_branchTaken
  // debug_branchPc := soc.io.debug_branchPc
  private val debugRegWrite = Wire(Bool())
  private val debugWriteAddr = Wire(UInt(5.W))
  private val debugWriteData = Wire(UInt(config.xlen.W))
  private val debugPc = Wire(UInt(config.xlen.W))
  private val debugBranchValid = Wire(Bool())
  private val debugBranchRs1 = Wire(UInt(config.xlen.W))
  private val debugBranchRs2 = Wire(UInt(config.xlen.W))
  private val debugBranchTaken = Wire(Bool())
  private val debugBranchPc = Wire(UInt(config.xlen.W))
  BoringUtils.addSink(debugRegWrite, CpuDebugTap.regWrite(debugTapId))
  BoringUtils.addSink(debugWriteAddr, CpuDebugTap.writeAddr(debugTapId))
  BoringUtils.addSink(debugWriteData, CpuDebugTap.writeData(debugTapId))
  BoringUtils.addSink(debugPc, CpuDebugTap.pc(debugTapId))
  BoringUtils.addSink(debugBranchValid, CpuDebugTap.branchValid(debugTapId))
  BoringUtils.addSink(debugBranchRs1, CpuDebugTap.branchRs1(debugTapId))
  BoringUtils.addSink(debugBranchRs2, CpuDebugTap.branchRs2(debugTapId))
  BoringUtils.addSink(debugBranchTaken, CpuDebugTap.branchTaken(debugTapId))
  BoringUtils.addSink(debugBranchPc, CpuDebugTap.branchPc(debugTapId))
  debug_regWrite := debugRegWrite
  debug_writeAddr := debugWriteAddr
  debug_writeData := debugWriteData
  debug_pc := debugPc
  debug_branchValid := debugBranchValid
  debug_branchRs1 := debugBranchRs1
  debug_branchRs2 := debugBranchRs2
  debug_branchTaken := debugBranchTaken
  debug_branchPc := debugBranchPc

  private val regfileData = Wire(UInt(config.xlen.W))
  BoringUtils.addSource(regfile_read_addr, RegFileProbe.addr(regfileProbeId))
  BoringUtils.addSource(regfile_read_en, RegFileProbe.en(regfileProbeId))
  BoringUtils.addSink(regfileData, RegFileProbe.data(regfileProbeId))
  regfile_read_data := regfileData
}
