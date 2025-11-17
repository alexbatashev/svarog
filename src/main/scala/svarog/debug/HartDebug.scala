package svarog.debug

import chisel3._
import chisel3.util._
import svarog.bits.RegFileReadIO
import svarog.bits.RegFileWriteIO

class Breakpoint(xlen: Int) extends Bundle {
  val pc = UInt(xlen.W)
}

class Watchpoint(xlen: Int) extends Bundle {
  val addr = UInt(xlen.W)
}

class RegisterDebugIO(xlen: Int) extends Bundle {
  val reg = UInt(5.W)
  val write = Bool()
  val data = UInt(xlen.W)
}

class HartDebugIO(xlen: Int) extends Bundle {
  val halt = Valid(Bool())
  val breakpoint = Valid(new Breakpoint(xlen))
  val watchpoint = Valid(new Watchpoint(xlen))
  val register = Valid(new RegisterDebugIO(xlen))
}

class HartDebugModule(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val hart = Flipped(new HartDebugIO(xlen: Int))

    val halt = Output(Bool())
    val watchpointTriggered = Output(Bool()) // Signal to HazardUnit

    val wbPC = Flipped(Valid(UInt(xlen.W)))
    val memStore = Flipped(Valid(UInt(xlen.W))) // Memory store address

    val regData = Valid(UInt(xlen.W))
    val regRead = Flipped(new RegFileReadIO(xlen))
    val regWrite = Flipped(new RegFileWriteIO(xlen))
  })

  val haltState = RegInit(false.B)

  io.halt := haltState

  when(io.hart.halt.valid) {
    haltState := io.hart.halt.bits
  }

  val regData = RegInit(0.U)
  val regValid = RegInit(false.B)

  io.regData.valid := regValid
  io.regData.bits := regData
  regValid := false.B

  io.regWrite.writeEn := false.B
  io.regWrite.writeAddr := 0.U
  io.regWrite.writeData := 0.U

  io.regRead.readAddr1 := 0.U
  io.regRead.readAddr2 := 0.U

  when(io.hart.register.valid) {
    when(io.hart.register.bits.write) {
      io.regWrite.writeEn := true.B
      io.regWrite.writeAddr := io.hart.register.bits.reg
      io.regWrite.writeData := io.hart.register.bits.data
    }.otherwise {
      io.regRead.readAddr1 := io.hart.register.bits.reg
      regValid := true.B
      regData := io.regRead.readData1
    }
  }

  val breakpointPC = RegInit(0.U)

  when(io.hart.breakpoint.valid) {
    breakpointPC := io.hart.breakpoint.bits.pc
  }

  when(io.wbPC.valid) {
    when(io.wbPC.bits === breakpointPC) {
      haltState := true.B
    }
  }

  // Watchpoint support
  val watchpointAddr = RegInit(0.U(xlen.W))
  val watchpointEnabled = RegInit(false.B)

  when(io.hart.watchpoint.valid) {
    watchpointAddr := io.hart.watchpoint.bits.addr
    watchpointEnabled := true.B
  }

  // Trigger watchpoint on store to watched address
  // Output signal to HazardUnit which will stall on next cycle
  io.watchpointTriggered := false.B
  when(io.memStore.valid && watchpointEnabled) {
    when(io.memStore.bits === watchpointAddr) {
      io.watchpointTriggered := true.B
    }
  }
}
