package svarog

import circt.stage.ChiselStage
import svarog.config.CpuSimConfig
import svarog.sim.VerilatorTop

/** Emits a Verilator-friendly SystemVerilog top that includes simulation memory. */
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
  private val dataMemBytes =
    cli
      .get("data-mem-bytes")
      .map(_.toInt)
      .orElse(cli.get("data-mem-kb").map(kb => kb.toInt * 1024))
      .getOrElse(4096)
  private val dataInitFile = cli.get("data-init-file")

  private val cfg = CpuSimConfig(
    xlen = xlen,
    dataMemBytes = dataMemBytes,
    dataInitFile = dataInitFile
  )

  ChiselStage.emitSystemVerilog(
    new VerilatorTop(cfg),
    Array(
      "--target-dir",
      cli.getOrElse("target-dir", "generated/verilator"),
      "--disable-all-randomization"
    )
  )

  println(
    s"Generated Verilator top with xlen=$xlen, dataMemBytes=$dataMemBytes" +
      dataInitFile.fold("")(file => s", dataInitFile=$file")
  )
}
