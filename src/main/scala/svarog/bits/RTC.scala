package svarog.bits

import chisel3._
import chisel3.util._
import freechips.rocketchip.util._

class RTC extends RawModule {
  val clk = IO(Input(Clock()))
  val reset = IO(Input(Bool()))

  val io = IO(new Bundle {
    val rtcClock = Input(Clock())
    val time = Output(UInt(64.W))
  })

  val crossing = Module(new AsyncQueue(UInt(64.W)))

  withClockAndReset(io.rtcClock, reset) {
    val counter = RegInit(0.U(64.W))
    counter := counter + 1.U

    crossing.io.enq.valid := true.B
    crossing.io.enq.bits := counter
  }

  crossing.io.enq_clock := io.rtcClock
  crossing.io.enq_reset := reset
  crossing.io.deq_clock := clk
  crossing.io.deq_reset := reset
  crossing.io.deq.ready := true.B

  withClockAndReset(clk, reset) {
    val time = RegInit(0.U(64.W))
    when(crossing.io.deq.valid) {
      time := crossing.io.deq.bits
    }
    io.time := time
  }
}
