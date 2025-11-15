package svarog.decoder

import chisel3._
import chisel3.util.Decoupled

class SimpleDecoder(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val inst = Flipped(Decoupled(new InstWord(xlen)))
    val decoded = Decoupled(new MicroOp(xlen))
  })

  val immGen = Module(new ImmGen(xlen))

  // Combinational decoder - always ready if downstream is ready
  io.inst.ready := io.decoded.ready

  // Output is valid when input is valid
  io.decoded.valid := io.inst.valid

  // Decode the instruction
  val decodedMicroOp = BaseInstructions(xlen)
    .decode(io.inst.bits.word, immGen)

  // Copy PC
  decodedMicroOp.pc := io.inst.bits.pc

  io.decoded.bits := decodedMicroOp
}
