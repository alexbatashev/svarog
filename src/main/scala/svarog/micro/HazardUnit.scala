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
    val watchpointHit = Input(Bool()) // Watchpoint trigger from debug module
    val stall = Output(Bool())
  })

  def hazardOn(reg: UInt, rs: UInt): Bool =
    reg =/= 0.U && rs =/= 0.U && reg === rs

  val hazardExec = io.decode.valid && io.exec.valid && (
    hazardOn(io.exec.bits, io.decode.bits.rs1) ||
      hazardOn(io.exec.bits, io.decode.bits.rs2)
  )

  val hazardMem = io.decode.valid && io.mem.valid && (
    hazardOn(io.mem.bits, io.decode.bits.rs1) ||
      hazardOn(io.mem.bits, io.decode.bits.rs2)
  )

  val hazardWb = io.decode.valid && io.wb.valid && (
    hazardOn(io.wb.bits, io.decode.bits.rs1) ||
      hazardOn(io.wb.bits, io.decode.bits.rs2)
  )

  io.stall := io.watchpointHit || hazardExec || hazardMem || hazardWb
}
