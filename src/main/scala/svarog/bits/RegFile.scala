package svarog.bits

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

object RegFileProbe {
  def addr(id: String): String = s"${id}_regfile_probe_addr"
  def en(id: String): String = s"${id}_regfile_probe_en"
  def data(id: String): String = s"${id}_regfile_probe_data"
}

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

class RegFile(xlen: Int, probeId: Option[String] = None) extends Module {
  val readIo = IO(new RegFileReadIO(xlen))
  val writeIo = IO(new RegFileWriteIO(xlen))
  val extraWriteEn = IO(Input(Bool()))
  val extraWriteAddr = IO(Input(UInt(5.W)))
  val extraWriteData = IO(Input(UInt(xlen.W)))

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(xlen.W))))

  when(writeIo.writeEn && writeIo.writeAddr =/= 0.U) {
    regs(writeIo.writeAddr) := writeIo.writeData
  }

  when(extraWriteEn && extraWriteAddr =/= 0.U) {
    regs(extraWriteAddr) := extraWriteData
  }

  readIo.readData1 := Mux(readIo.readAddr1 === 0.U, 0.U, regs(readIo.readAddr1))
  readIo.readData2 := Mux(readIo.readAddr2 === 0.U, 0.U, regs(readIo.readAddr2))

  probeId.foreach { id =>
    val probeAddr = WireDefault(0.U(5.W))
    val probeEnable = WireDefault(false.B)
    BoringUtils.addSink(probeAddr, RegFileProbe.addr(id))
    BoringUtils.addSink(probeEnable, RegFileProbe.en(id))

    val probeData = Wire(UInt(xlen.W))
    probeData := Mux(probeEnable, regs(probeAddr), 0.U)
    BoringUtils.addSource(probeData, RegFileProbe.data(id))
  }
}
