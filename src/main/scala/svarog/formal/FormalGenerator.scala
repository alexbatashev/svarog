// Elaborates `FormalTop` to Verilog for BTOR2 model checking. Reuses the SoC
// YAML config to obtain a cluster definition; memory is abstracted inside
// `FormalTop`, so only the cluster and a base address are needed.
//
//   ./mill svarog.runMain svarog.formal.FormalGenerator \
//       --config=configs/svg-micro.yaml --target-dir=out/formal
//
// Then: yosys -p 'read_verilog -sv out/formal/FormalTop.sv; \
//                 prep -top FormalTop; flatten; setundef -zero; \
//                 write_btor2 impl.btor2'

package svarog.formal

import chisel3._
import circt.stage.FirtoolOption
import firrtl.seqToAnnoSeq
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import org.chipsalliance.diplomacy.nodes.MonitorsEnabled
import svarog.config.{ConfigLoader, SoC}

object FormalGenerator extends App {
  private val cli = args
    .flatMap { arg =>
      arg.stripPrefix("--").split("=", 2) match {
        case Array(k, v) => Some(k -> v)
        case _           => None
      }
    }
    .toMap

  private val configPath = cli.getOrElse("config", {
    System.err.println("Error: --config is required")
    sys.exit(1)
  })
  private val targetDir = cli.getOrElse("target-dir", "out/formal")

  private val yaml = ConfigLoader.loadSoCConfig(configPath) match {
    case Right(c) => c
    case Left(err) =>
      System.err.println(s"Error loading config: $err")
      sys.exit(1)
  }
  private val config = SoC.fromYaml(yaml, simulatorDebug = false)
  private val cluster = config.clusters.head
  private val baseAddress = config.memories.headOption.map(_.getBaseAddress).getOrElse(0x80000000L)

  implicit val p: Parameters = Parameters.empty.alterPartial {
    case MonitorsEnabled => false
  }

  emitVerilog(
    LazyModule(new FormalTop(hartId = 0, cluster = cluster, startAddress = baseAddress)).module,
    Array("--target-dir", targetDir),
    Seq(
      FirtoolOption("--disable-all-randomization"),
      FirtoolOption("--default-layer-specialization=enable"),
      // Hoist block-local `automatic` variables so the Yosys Verilog frontend
      // (used to reach BTOR2) can parse the output.
      FirtoolOption(
        "--lowering-options=disallowPortDeclSharing,disallowLocalVariables,disallowPackedArrays"
      )
    )
  )
}
