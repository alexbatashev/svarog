package svarog.decoder

import chisel3._
import chisel3.util.{Decoupled, Valid}

class SimpleDecodeHazardIO extends Bundle {
  val rs1 = Output(UInt(5.W))
  val rs2 = Output(UInt(5.W))
}

class SimpleDecoder(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val inst = Flipped(Decoupled(new InstWord(xlen)))
    val decoded = Decoupled(new MicroOp(xlen))
    val hazard = Valid(new SimpleDecodeHazardIO)
  })

  val immGen = Module(new ImmGen(xlen))

  // Combinational decoder - always ready if downstream is ready
  io.inst.ready := io.decoded.ready

  // Output is valid when input is valid
  io.decoded.valid := io.inst.valid

  // Decode the instruction
  val baseDecoder = Module(new BaseInstructions(xlen))
  baseDecoder.io.immGen <> immGen.io
  baseDecoder.io.instruction := io.inst.bits.word
  baseDecoder.io.pc := io.inst.bits.pc

  val mDecoder = Some(Module(new MInstructions(xlen)))

  io.decoded.bits := MicroOp.getInvalid(xlen)

  when(baseDecoder.io.decoded.opType =/= OpType.INVALID) {
    io.decoded.bits := baseDecoder.io.decoded
  }

  mDecoder.foreach { mDecoder =>
    mDecoder.io.instruction := io.inst.bits.word
    mDecoder.io.pc := io.inst.bits.pc

    when(mDecoder.io.decoded.opType =/= OpType.INVALID) {
      io.decoded.bits := mDecoder.io.decoded
    }
  }

  io.hazard.valid := io.inst.valid
  io.hazard.bits.rs1 := io.decoded.bits.rs1
  io.hazard.bits.rs2 := io.decoded.bits.rs2
}
