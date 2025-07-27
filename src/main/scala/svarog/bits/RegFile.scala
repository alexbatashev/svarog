package svarog.bits

import chisel3._
import chisel3.util._

class RegFileIO(xlen: Int) extends Bundle {
  val writeEn = Input(Bool())
  val writeAddr = Input(UInt(5.W))
  val writeData = Input(UInt(xlen.W))

  val readAddr1 = Input(UInt(5.W))
  val readData1 = Output(UInt(xlen.W))

  val readAddr2 = Input(UInt(5.W))
  val readData2 = Output(UInt(xlen.W))
}

class RegFile(xlen: Int) extends Module {
  val io = IO(new RegFileIO(xlen))

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(xlen.W))))

  when(io.writeEn && io.writeAddr =/= 0.U) {
    regs(io.writeAddr) := io.writeData
  }

  io.readData1 := Mux(io.readAddr1 === 0.U, 0.U, regs(io.readAddr1))
  io.readData2 := Mux(io.readAddr2 === 0.U, 0.U, regs(io.readAddr2))
}
