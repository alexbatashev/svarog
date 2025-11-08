package svarog.bits

import chisel3._
import chisel3.util._
import svarog.memory.DataCacheIO

/** Routes CPU data-cache requests between RAM and UART peripheral via simple address decode. */
class PeriphMux(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val cpu = Flipped(new DataCacheIO(xlen))
    val ram = new DataCacheIO(xlen)
    val uart = new DataCacheIO(xlen)
  })

  val cpuReq = io.cpu.req
  val addr = cpuReq.bits.addr
  val selectUart = addr(31, 28) === "h1".U

  // Forward requests
  io.ram.req.bits := cpuReq.bits
  io.uart.req.bits := cpuReq.bits
  io.ram.req.valid := cpuReq.valid && !selectUart
  io.uart.req.valid := cpuReq.valid && selectUart
  io.cpu.req.ready := Mux(selectUart, io.uart.req.ready, io.ram.req.ready)

  // Track which target should drive the response
  val activeIsUart = RegInit(false.B)
  when(cpuReq.fire) {
    activeIsUart := selectUart
  }

  val ramRespValid = io.ram.resp.valid
  val uartRespValid = io.uart.resp.valid

  io.cpu.resp.bits := Mux(activeIsUart, io.uart.resp.bits, io.ram.resp.bits)
  io.cpu.resp.valid := Mux(activeIsUart, uartRespValid, ramRespValid)

  io.ram.resp.ready := !activeIsUart && io.cpu.resp.ready
  io.uart.resp.ready := activeIsUart && io.cpu.resp.ready
}
