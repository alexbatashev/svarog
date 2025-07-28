package svarog.memory

import chisel3._
import chisel3.util._

class L1CacheCpuIO(addrWidth: Int, dataWidth: Int) extends Bundle {
  val reqValid  = Input(Bool())
  val addr      = Input(UInt(addrWidth.W))
  val respValid = Output(Bool())
  val data      = Output(UInt(dataWidth.W))
}

class L1CacheIO(addrWidth: Int, dataWidth: Int, lineSize: Int) extends Bundle {
  val cpu = new L1CacheCpuIO(addrWidth, dataWidth)

  val mem = new Bundle {
    val reqValid  = Output(Bool())
    val addr      = Output(UInt(addrWidth.W))
    val respValid = Input(Bool())
    val dataIn    = Input(Vec(lineSize / (dataWidth/8), UInt(dataWidth.W)))
    val dataOut   = Output(Vec(lineSize / (dataWidth/8), UInt(dataWidth.W)))
    val writeEn   = Output(Bool())
  }
}
