package svarog

import chisel3._
import chisel3.util.{Decoupled, log2Ceil}
import circt.stage.FirtoolOption
import svarog.{SvarogSoC, SvarogConfig}
import svarog.config.{ConfigLoader, BootloaderValidator}
import svarog.VerilogGenerator.{bootloaderPath => bootloaderPath}
import svarog.VerilogGenerator.{validatedBootloader => validatedBootloader}

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
        "Usage: --config=path/to/config.yaml --target-dir=output/dir [--bootloader=path/to/boot.hex]"
      )
      sys.exit(1)
    }
  )

  // Optional arguments
  private val targetDir = cli.getOrElse("target-dir", "target/generated/")
  private val bootloaderPath = cli.get("bootloader")

  // Validate bootloader if provided
  private val validatedBootloader = bootloaderPath.flatMap { path =>
    BootloaderValidator.validate(path) match {
      case Right(validated) => Some(validated)
      case Left(err) =>
        System.err.println(s"Error: $err")
        sys.exit(1)
    }
  }

  // Load and validate config
  private val config = ConfigLoader.loadSvarogConfig(
    configPath,
    validatedBootloader
  ) match {
    case Right(cfg) => cfg
    case Left(err) =>
      System.err.println(s"Error loading config: $err")
      sys.exit(1)
  }

  // Generate Verilog
  emitVerilog(
    new SvarogSoC(config, validatedBootloader),
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
