package svarog

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}
import circt.stage.FirtoolOption
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.nodes.MonitorsEnabled
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import svarog.SvarogSoC
import svarog.config.{ConfigLoader, BootloaderValidator, SoC}
import svarog.VerilogGenerator.{bootloaderPath => bootloaderPath}
import svarog.VerilogGenerator.{validatedBootloader => validatedBootloader}
import firrtl.seqToAnnoSeq

object VerilogGenerator extends App {
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

  // Required arguments
  private val configPath = cli.getOrElse(
    "config", {
      System.err.println("Error: --config argument is required")
      System.err.println(
        "Usage: --config=path/to/config.yaml --target-dir=output/dir [--bootloader=path/to/boot.hex] [--simulator-debug-iface=true|false]"
      )
      sys.exit(1)
    }
  )

  // Optional arguments
  private val targetDir = cli.getOrElse("target-dir", "target/generated/")
  private val bootloaderPath = cli.get("bootloader")
  private val simulatorDebugIface =
    cli.get("simulator-debug-iface").exists(_.toLowerCase == "true")
  private val monitorsEnabled =
    cli.get("with-monitors").exists(_.toLowerCase == "true")

  // Validate bootloader if provided
  private val validatedBootloader = bootloaderPath.flatMap { path =>
    BootloaderValidator.validate(path) match {
      case Right(validated) => Some(validated)
      case Left(err) =>
        System.err.println(s"Error: $err")
        sys.exit(1)
    }
  }

  // Load YAML config
  private val yamlConfig = ConfigLoader.loadSoCConfig(configPath) match {
    case Right(cfg) => cfg
    case Left(err) =>
      System.err.println(s"Error loading config: $err")
      sys.exit(1)
  }

  // Build complete SoC config with runtime flags
  private val config = SoC.fromYaml(yamlConfig, simulatorDebugIface)

  // Generate Verilog
  implicit val p: Parameters = Parameters.empty.alterPartial {
    case MonitorsEnabled => monitorsEnabled
  }

  emitVerilog(
    LazyModule(new SvarogSoC(config, validatedBootloader)).module,
    // new VerilatorTop(config, validatedBootloader),
    Array("--target-dir", targetDir),
    Seq(
      FirtoolOption("--disable-all-randomization"),
      FirtoolOption("--default-layer-specialization=enable"),
      FirtoolOption(
        "--lowering-options=disallowPortDeclSharing,printDebugInfo"
      ),
      FirtoolOption("--preserve-values=all"),
      FirtoolOption("--verification-flavor=sva")
    )
  )
}
