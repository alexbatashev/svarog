package svarog.dsp

import chisel3._

/** Emits standalone Verilog for the VLIW DSP block so it can be inspected or
  * dropped into an external testbench.
  *
  * Run with:
  * {{{
  *   ./mill -i svarog.runMain svarog.dsp.GenerateVliwDsp --target-dir=target/dsp/
  *   # optional: --lanes=4 --regs=16 --xlen=32
  * }}}
  */
object GenerateVliwDsp extends App {
  private val cli = args.toList.flatMap { raw =>
    val t = raw.trim
    if (t.startsWith("--") && t.contains("=")) {
      val Array(k, v) = t.drop(2).split("=", 2)
      Some(k -> v)
    } else None
  }.toMap

  private val targetDir = cli.getOrElse("target-dir", "target/dsp/")
  private val params = DspParams(
    xlen = cli.get("xlen").map(_.toInt).getOrElse(32),
    numLanes = cli.get("lanes").map(_.toInt).getOrElse(4),
    numRegs = cli.get("regs").map(_.toInt).getOrElse(16)
  )

  println(
    s"Generating VliwDsp: xlen=${params.xlen} lanes=${params.numLanes} " +
      s"regs=${params.numRegs} bundleBits=${params.bundleBits} -> $targetDir"
  )

  emitVerilog(new VliwDsp(params), Array("--target-dir", targetDir))
}
