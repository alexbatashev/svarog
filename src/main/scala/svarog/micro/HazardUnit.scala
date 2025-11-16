package svarog.micro

import chisel3._
import chisel3.util._
import svarog.decoder.SimpleDecodeHazardIO

class HazardUnit extends Module {
  val io = IO(new Bundle {
    val decode = Flipped(Valid(new SimpleDecodeHazardIO))
    val exec = Flipped(Valid(UInt(5.W)))
    val mem = Flipped(Valid(UInt(5.W)))
    val wb = Flipped(Valid(UInt(5.W)))
    val stall = Output(Bool())
  })

  val mustStall = RegInit(false.B)
  val stallReg = RegInit(0.U)

  io.stall := mustStall

  when(io.exec.valid && io.decode.valid && io.exec.bits =/= 0.U) {
    val hazardOnRs1 =
      io.exec.bits === io.decode.bits.rs1 && io.decode.bits.rs1 =/= 0.U
    val hazardOnRs2 =
      io.exec.bits === io.decode.bits.rs2 && io.decode.bits.rs2 =/= 0.U
    when(hazardOnRs1 || hazardOnRs2) {
      mustStall := true.B
      // Immediately stall the pipeline
      io.stall := true.B
      stallReg := io.exec.bits
    }
  }

  when(io.mem.valid && io.decode.valid && io.mem.bits =/= 0.U) {
    val hazardOnRs1 =
      io.mem.bits === io.decode.bits.rs1 && io.decode.bits.rs1 =/= 0.U
    val hazardOnRs2 =
      io.mem.bits === io.decode.bits.rs2 && io.decode.bits.rs2 =/= 0.U
    when(hazardOnRs1 || hazardOnRs2) {
      mustStall := true.B
      // Immediately stall the pipeline
      io.stall := true.B
      stallReg := io.mem.bits
    }
  }

  when(mustStall && io.wb.valid && io.wb.bits =/= 0.U) {
    when(io.wb.bits === stallReg) {
      // Release stall on next cycle since reg file has no bypass
      mustStall := false.B
    }
  }
}
