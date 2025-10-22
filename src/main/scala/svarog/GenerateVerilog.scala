package svarog

import chisel3._
import circt.stage.ChiselStage
import svarog.micro.Cpu

object GenerateVerilog extends App {
  val targetDir = "generated"

  // Generate Verilog for your CPU
  emitVerilog(
    new Cpu(32),
    Array("--target-dir", targetDir)
  )

  println(s"Verilog generated in $targetDir/Cpu.v")
}
