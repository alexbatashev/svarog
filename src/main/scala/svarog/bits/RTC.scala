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

  val counterGray = withClockAndReset(io.rtcClock, reset) {
    val counter = RegInit(0.U(64.W))
    counter := counter + 1.U

    val gray = RegInit(0.U(64.W))
    gray := counter ^ (counter >> 1)
    gray
  }

  withClockAndReset(clk, reset) {
    val graySync = AsyncResetSynchronizerShiftReg(
      in = counterGray,
      sync = 3,
      name = Some("rtc_gray_sync")
    )

    val timeBinary = Wire(UInt(64.W))
    val binaryBits = (0 until 64).map { i =>
      (graySync >> i).xorR.asUInt
    }.reverse
    timeBinary := Cat(binaryBits)

    io.time := RegNext(timeBinary)
  }
}
