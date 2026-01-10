package svarog.micro

import chisel3._
import chisel3.util._
import svarog.decoder.SimpleDecodeHazardIO

class HazardUnitCSRIO extends Bundle {
  val addr = UInt(12.W)
  val isWrite = Bool()
  val isTrap = Bool()  // Trap instruction writes multiple CSRs (mepc, mcause, mtval)
}

class HazardUnit extends Module {
  val io = IO(new Bundle {
    val decode = Flipped(Valid(new SimpleDecodeHazardIO))
    val exec = Flipped(Valid(UInt(5.W)))
    val mem = Flipped(Valid(UInt(5.W)))
    val wb = Flipped(Valid(UInt(5.W)))
    val execCsr = Flipped(Valid(new HazardUnitCSRIO))
    val memCsr = Flipped(Valid(new HazardUnitCSRIO))
    val wbCsr = Flipped(Valid(new HazardUnitCSRIO))
    val watchpointHit = Input(Bool()) // Watchpoint trigger from debug module
    val stall = Output(Bool())
  })

  def hazardOn(reg: UInt, rs: UInt): Bool =
    reg =/= 0.U && rs =/= 0.U && reg === rs

  def csrHazardOn(csrAddr: UInt, decodeCsrAddr: UInt, isWrite: Bool): Bool =
    isWrite && csrAddr === decodeCsrAddr

  // Trap hazard: stall if reading trap CSRs while trap is in pipeline
  def trapHazardOn(decodeCsrAddr: UInt, isTrap: Bool): Bool = {
    val readingTrapCsr = (decodeCsrAddr === 0x305.U ||  // mtvec
                          decodeCsrAddr === 0x341.U ||  // mepc
                          decodeCsrAddr === 0x342.U ||  // mcause
                          decodeCsrAddr === 0x343.U)    // mtval
    isTrap && readingTrapCsr
  }

  // GPR hazards
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

  // CSR hazards - stall if decode has a CSR op and there's a pending CSR write to the same address
  val csrHazardExec =
    io.decode.valid && io.decode.bits.isCsrOp && io.execCsr.valid &&
      csrHazardOn(
        io.execCsr.bits.addr,
        io.decode.bits.csrAddr,
        io.execCsr.bits.isWrite
      )

  val csrHazardMem =
    io.decode.valid && io.decode.bits.isCsrOp && io.memCsr.valid &&
      csrHazardOn(
        io.memCsr.bits.addr,
        io.decode.bits.csrAddr,
        io.memCsr.bits.isWrite
      )

  val csrHazardWb =
    io.decode.valid && io.decode.bits.isCsrOp && io.wbCsr.valid &&
      csrHazardOn(
        io.wbCsr.bits.addr,
        io.decode.bits.csrAddr,
        io.wbCsr.bits.isWrite
      )

  // Trap hazards - stall if reading trap CSRs while trap instruction is in pipeline
  val trapHazardExec =
    io.decode.valid && io.decode.bits.isCsrOp && io.execCsr.valid &&
      trapHazardOn(io.decode.bits.csrAddr, io.execCsr.bits.isTrap)

  val trapHazardMem =
    io.decode.valid && io.decode.bits.isCsrOp && io.memCsr.valid &&
      trapHazardOn(io.decode.bits.csrAddr, io.memCsr.bits.isTrap)

  val trapHazardWb =
    io.decode.valid && io.decode.bits.isCsrOp && io.wbCsr.valid &&
      trapHazardOn(io.decode.bits.csrAddr, io.wbCsr.bits.isTrap)

  io.stall := io.watchpointHit || hazardExec || hazardMem || hazardWb ||
    csrHazardExec || csrHazardMem || csrHazardWb ||
    trapHazardExec || trapHazardMem || trapHazardWb
}
