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

  val config = SvarogConfig(
    xlen = xlen,
    memSizeBytes = ramSizeBytes,
    clockHz = clockHz,
    enableDebugInterface = true
  )

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

  val mem_write_en = IO(Input(Bool()))
  val mem_write_addr = IO(Input(UInt(config.xlen.W)))
  val mem_write_data = IO(Input(UInt(8.W)))
  val halt = IO(Input(Bool()))
  val tohost_addr = IO(Input(UInt(config.xlen.W)))

  val debug_regWrite = IO(Output(Bool()))
  val debug_writeAddr = IO(Output(UInt(5.W)))
  val debug_writeData = IO(Output(UInt(config.xlen.W)))
  val debug_pc = IO(Output(UInt(config.xlen.W)))
  val debug_branchValid = IO(Output(Bool()))
  val debug_branchRs1 = IO(Output(UInt(config.xlen.W)))
  val debug_branchRs2 = IO(Output(UInt(config.xlen.W)))
  val debug_branchTaken = IO(Output(Bool()))
  val debug_branchPc = IO(Output(UInt(config.xlen.W)))
  val debug_flush = IO(Output(Bool()))
  val debug_bootHold = IO(Output(Bool()))
  val debug_decodeValid = IO(Output(Bool()))
  val debug_decodeRegWrite = IO(Output(Bool()))
  val debug_instruction = IO(Output(UInt(32.W)))
  // ALU debug outputs
  val debug_aluValid = IO(Output(Bool()))
  val debug_aluPc = IO(Output(UInt(config.xlen.W)))
  val debug_aluOp = IO(Output(UInt(4.W)))
  val debug_aluHasImm = IO(Output(Bool()))
  val debug_aluRs1 = IO(Output(UInt(5.W)))
  val debug_aluRs2 = IO(Output(UInt(5.W)))
  val debug_aluRd = IO(Output(UInt(5.W)))
  val debug_aluInput1 = IO(Output(UInt(config.xlen.W)))
  val debug_aluInput2 = IO(Output(UInt(config.xlen.W)))
  val debug_aluOutput = IO(Output(UInt(config.xlen.W)))
  val debug_aluRegWrite = IO(Output(Bool()))
  val debug_memRegWrite = IO(Output(Bool()))
  val debug_memWbRegWrite = IO(Output(Bool()))
  val debug_memPc = IO(Output(UInt(config.xlen.W)))
  val debug_memRd = IO(Output(UInt(5.W)))
  val debug_wbRegWrite = IO(Output(Bool()))
  val debug_wbRd = IO(Output(UInt(5.W)))
  val debug_wbPc = IO(Output(UInt(config.xlen.W)))

  val regfile_read_en = IO(Input(Bool()))
  val regfile_read_addr = IO(Input(UInt(5.W)))
  val regfile_read_data = IO(Output(UInt(config.xlen.W)))

  private val soc = Module(
    new SvarogSoC(
      config
      // regfileProbeId = Some(regfileProbeId)
    )
  )

  soc.io.mem_write_en := mem_write_en
  soc.io.mem_write_addr := mem_write_addr
  soc.io.mem_write_data := mem_write_data
  soc.io.halt := halt
  soc.io.tohostAddr := tohost_addr
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
  private val debugFlush = Wire(Bool())
  private val debugBootHold = Wire(Bool())
  private val debugDecodeValid = Wire(Bool())
  private val debugDecodeRegWrite = Wire(Bool())
  private val debugInstruction = Wire(UInt(32.W))
  private val debugAluValid = Wire(Bool())
  private val debugAluPc = Wire(UInt(config.xlen.W))
  private val debugAluOp = Wire(UInt(4.W))
  private val debugAluHasImm = Wire(Bool())
  private val debugAluRs1 = Wire(UInt(5.W))
  private val debugAluRs2 = Wire(UInt(5.W))
  private val debugAluRd = Wire(UInt(5.W))
  private val debugAluInput1 = Wire(UInt(config.xlen.W))
  private val debugAluInput2 = Wire(UInt(config.xlen.W))
  private val debugAluOutput = Wire(UInt(config.xlen.W))
  private val debugAluRegWrite = Wire(Bool())
  private val debugMemRegWrite = Wire(Bool())
  private val debugMemWbRegWrite = Wire(Bool())
  private val debugMemPc = Wire(UInt(config.xlen.W))
  private val debugMemRd = Wire(UInt(5.W))
  private val debugWbRegWrite = Wire(Bool())
  private val debugWbRd = Wire(UInt(5.W))
  private val debugWbPc = Wire(UInt(config.xlen.W))

  BoringUtils.addSink(debugRegWrite, CpuDebugTap.regWrite(debugTapId))
  BoringUtils.addSink(debugWriteAddr, CpuDebugTap.writeAddr(debugTapId))
  BoringUtils.addSink(debugWriteData, CpuDebugTap.writeData(debugTapId))
  BoringUtils.addSink(debugPc, CpuDebugTap.pc(debugTapId))
  BoringUtils.addSink(debugBranchValid, CpuDebugTap.branchValid(debugTapId))
  BoringUtils.addSink(debugBranchRs1, CpuDebugTap.branchRs1(debugTapId))
  BoringUtils.addSink(debugBranchRs2, CpuDebugTap.branchRs2(debugTapId))
  BoringUtils.addSink(debugBranchTaken, CpuDebugTap.branchTaken(debugTapId))
  BoringUtils.addSink(debugBranchPc, CpuDebugTap.branchPc(debugTapId))
  BoringUtils.addSink(debugFlush, CpuDebugTap.flush(debugTapId))
  BoringUtils.addSink(debugBootHold, CpuDebugTap.bootHold(debugTapId))
  BoringUtils.addSink(debugDecodeValid, CpuDebugTap.decodeValid(debugTapId))
  BoringUtils.addSink(
    debugDecodeRegWrite,
    CpuDebugTap.decodeRegWrite(debugTapId)
  )
  BoringUtils.addSink(debugInstruction, CpuDebugTap.instruction(debugTapId))
  BoringUtils.addSink(debugAluValid, CpuDebugTap.aluValid(debugTapId))
  BoringUtils.addSink(debugAluPc, CpuDebugTap.aluPc(debugTapId))
  BoringUtils.addSink(debugAluOp, CpuDebugTap.aluOp(debugTapId))
  BoringUtils.addSink(debugAluHasImm, CpuDebugTap.aluHasImm(debugTapId))
  BoringUtils.addSink(debugAluRs1, CpuDebugTap.aluRs1(debugTapId))
  BoringUtils.addSink(debugAluRs2, CpuDebugTap.aluRs2(debugTapId))
  BoringUtils.addSink(debugAluRd, CpuDebugTap.aluRd(debugTapId))
  BoringUtils.addSink(debugAluInput1, CpuDebugTap.aluInput1(debugTapId))
  BoringUtils.addSink(debugAluInput2, CpuDebugTap.aluInput2(debugTapId))
  BoringUtils.addSink(debugAluOutput, CpuDebugTap.aluOutput(debugTapId))
  BoringUtils.addSink(debugAluRegWrite, CpuDebugTap.aluRegWrite(debugTapId))
  BoringUtils.addSink(debugMemRegWrite, CpuDebugTap.memRegWrite(debugTapId))
  BoringUtils.addSink(debugMemWbRegWrite, CpuDebugTap.memWbRegWrite(debugTapId))
  BoringUtils.addSink(debugMemPc, CpuDebugTap.memPc(debugTapId))
  BoringUtils.addSink(debugMemRd, CpuDebugTap.memRd(debugTapId))
  BoringUtils.addSink(debugWbRegWrite, CpuDebugTap.wbRegWrite(debugTapId))
  BoringUtils.addSink(debugWbRd, CpuDebugTap.wbRd(debugTapId))
  BoringUtils.addSink(debugWbPc, CpuDebugTap.wbPc(debugTapId))

  debug_regWrite := debugRegWrite
  debug_writeAddr := debugWriteAddr
  debug_writeData := debugWriteData
  debug_pc := debugPc
  debug_branchValid := debugBranchValid
  debug_branchRs1 := debugBranchRs1
  debug_branchRs2 := debugBranchRs2
  debug_branchTaken := debugBranchTaken
  debug_branchPc := debugBranchPc
  debug_flush := debugFlush
  debug_bootHold := debugBootHold
  debug_decodeValid := debugDecodeValid
  debug_decodeRegWrite := debugDecodeRegWrite
  debug_instruction := debugInstruction
  debug_aluValid := debugAluValid
  debug_aluPc := debugAluPc
  debug_aluOp := debugAluOp
  debug_aluHasImm := debugAluHasImm
  debug_aluRs1 := debugAluRs1
  debug_aluRs2 := debugAluRs2
  debug_aluRd := debugAluRd
  debug_aluInput1 := debugAluInput1
  debug_aluInput2 := debugAluInput2
  debug_aluOutput := debugAluOutput
  debug_aluRegWrite := debugAluRegWrite
  debug_memRegWrite := debugMemRegWrite
  debug_memWbRegWrite := debugMemWbRegWrite
  debug_memPc := debugMemPc
  debug_memRd := debugMemRd
  debug_wbRegWrite := debugWbRegWrite
  debug_wbRd := debugWbRd
  debug_wbPc := debugWbPc

  private val regfileData = Wire(UInt(config.xlen.W))
  BoringUtils.addSource(regfile_read_addr, RegFileProbe.addr(regfileProbeId))
  BoringUtils.addSource(regfile_read_en, RegFileProbe.en(regfileProbeId))
  BoringUtils.addSink(regfileData, RegFileProbe.data(regfileProbeId))
  regfile_read_data := regfileData
}
