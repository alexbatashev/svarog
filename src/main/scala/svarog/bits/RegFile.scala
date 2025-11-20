package svarog.bits

import chisel3._
import chisel3.util._

class RegFileReadIO(xlen: Int) extends Bundle {
  val readAddr1 = Input(UInt(5.W))
  val readData1 = Output(UInt(xlen.W))

  val readAddr2 = Input(UInt(5.W))
  val readData2 = Output(UInt(xlen.W))
}

class RegFileWriteIO(xlen: Int) extends Bundle {
  val writeEn = Input(Bool())
  val writeAddr = Input(UInt(5.W))
  val writeData = Input(UInt(xlen.W))
}

class RegFile(xlen: Int) extends Module {
  val readIo = IO(new RegFileReadIO(xlen))
  val writeIo = IO(new RegFileWriteIO(xlen))

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(xlen.W))))

  when(writeIo.writeEn && writeIo.writeAddr =/= 0.U) {
    regs(writeIo.writeAddr) := writeIo.writeData
  }

  readIo.readData1 := Mux(readIo.readAddr1 === 0.U, 0.U, regs(readIo.readAddr1))
  readIo.readData2 := Mux(readIo.readAddr2 === 0.U, 0.U, regs(readIo.readAddr2))
}
