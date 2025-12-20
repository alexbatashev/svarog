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
    new VerilatorTop(config, validatedBootloader),
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

class VerilatorTop(
    config: SvarogConfig,
    bootloader: Option[String]
) extends Module {
  val io = IO(new Bundle {
    val debug = if (config.soc.enableDebug) {
      Some(new Bundle {
        val hart_in =
          Flipped(new svarog.debug.ChipHartDebugIO(config.cores.head.xlen))
        val mem_in =
          Flipped(
            Decoupled(
              new svarog.debug.ChipMemoryDebugIO(config.cores.head.xlen)
            )
          )
        val mem_res = Decoupled(UInt(config.cores.head.xlen.W))
        val reg_res = Decoupled(UInt(config.cores.head.xlen.W))
        val halted = Output(Bool())
      })
    } else None

    val uarts = Vec(
      config.soc.uarts.filter(_.enabled).length,
      new Bundle {
        val txd = Output(Bool())
        val rxd = Input(Bool())
      }
    )
  })

  private val soc = Module(
    new SvarogSoC(config, bootloader)
  )

  if (config.soc.enableDebug) {
    soc.io.debug.hart_in <> io.debug.get.hart_in
    soc.io.debug.mem_in <> io.debug.get.mem_in
    soc.io.debug.mem_res <> io.debug.get.mem_res
    soc.io.debug.reg_res <> io.debug.get.reg_res
    io.debug.get.halted <> soc.io.debug.halted
  }

  // Connect UART IO
  for (i <- 0 until config.soc.uarts.filter(_.enabled).length) {
    soc.io.uarts(i) <> io.uarts(i)
  }
}
