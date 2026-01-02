package svarog.perf

import chisel3._
import chisel3.util._

import svarog.config.ISA
import svarog.bits.CSRDeviceIO
import svarog.bits.CSRBusDevice

class HPM(isa: ISA) extends Module with CSRBusDevice {
  val io = IO(new Bundle {
    val instretCount = Input(UInt(1.W)) // Only need one bit for now
  })

  val bus = IO(new CSRDeviceIO(isa.xlen))

  private val csrMcycle = "hB00".U
  private val csrMinstret = "hB02".U
  private val csrTime = "hC01".U
  private val csrMcycleh = "hB80".U
  private val csrMinstreth = "hB82".U
  private val csrTimeh = "hC81".U

  def addrInRange(addr: UInt): Bool = {
    val result = Wire(Bool())
    result := false.B

    when(addr === csrMcycle) {
      result := true.B
    }.elsewhen(addr === csrMinstret) {
      result := true.B
    }.elsewhen(addr === csrTime) {
      result := true.B
    }

    if (isa.xlen == 32) {
      when(addr === csrMcycleh) {
        result := true.B
      }.elsewhen(addr === csrMinstreth) {
        result := true.B
        // }.elsewhen(addr === csrTimeh) {
        //   result := true.B
      }
    }

    result
  }

  private val cycles = RegInit(0.U(64.W))
  private val instret = RegInit(0.U(64.W))

  cycles := cycles + 1.U
  instret := instret + io.instretCount

  bus.read.bits.data := 0.U
  bus.write.bits.error := false.B

  when(bus.read.valid) {
    switch(bus.read.bits.addr) {
      is(csrMcycle) {
        bus.read.bits.data := cycles(isa.xlen - 1, 0)
      }
      is(csrMinstret) {
        bus.read.bits.data := instret(isa.xlen - 1, 0)
      }
    }
  }

  when(bus.write.valid) {
    switch(bus.write.bits.addr) {
      is(csrMcycle) {
        cycles(isa.xlen - 1, 0) := bus.write.bits.data
      }
      is(csrMinstret) {
        instret(isa.xlen - 1, 0) := bus.write.bits.data
      }
    }
  }
}
