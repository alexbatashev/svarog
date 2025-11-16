package svarog

import chisel3._
import circt.stage.FirtoolOption
import chisel3.util.log2Ceil
import svarog.soc.SvarogSoC
import svarog.bits.RegFileProbe
import chisel3.util.experimental.BoringUtils
import svarog.soc.SvarogConfig

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
  private val soc = Module(
    new SvarogSoC(
      config
    )
  )
}
