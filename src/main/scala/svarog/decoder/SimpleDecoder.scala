package svarog.decoder

import chisel3._

class SimpleDecoder(xlen: Int) extends BaseDecoder(xlen, 1) {
  val immGen = Module(new ImmGen(xlen))

  // Combinational decoder - always ready if downstream is ready
  io.inst.ready := io.decoded.ready

  // Output is valid when input is valid
  io.decoded.valid := io.inst.valid

  // Decode the instruction
  val decodedMicroOp = BaseInstructions(xlen)
    .decode(io.inst.bits(0).word, immGen)

  // Copy PC
  decodedMicroOp.pc := io.inst.bits(0).pc

  io.decoded.bits(0) := decodedMicroOp
}
