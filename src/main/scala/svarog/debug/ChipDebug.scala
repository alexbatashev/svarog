package svarog.debug

import chisel3._
import chisel3.util._
import svarog.memory.MemWidth
import svarog.memory.MemoryRequest
import svarog.memory.MemoryIO

class ChipHartDebugIO(xlen: Int) extends Bundle {
  val id = Valid(UInt(8.W))
  val bits = new HartDebugIO(xlen)
}

class ChipMemoryDebugIO(xlen: Int) extends Bundle {
  val addr = UInt(xlen.W)
  val reqWidth = MemWidth()
  val instr = Bool()

  val write = Bool()
  val data = UInt(xlen.W)
}

class ChipDebugModule(xlen: Int, numHarts: Int) extends Module {
  val io = IO(new Bundle {
    val hart_in = Flipped(new ChipHartDebugIO(xlen))
    val mem_in = Flipped(Decoupled(new ChipMemoryDebugIO(xlen)))

    val harts = Vec(numHarts, new HartDebugIO(xlen))
    val cpuRegData = Flipped(Valid(UInt(xlen.W))) // Register data from CPU

    val imem_iface = new MemoryIO(xlen, xlen)
    val dmem_iface = new MemoryIO(xlen, xlen)

    val mem_res = Decoupled(UInt(xlen.W))
    val reg_res = Decoupled(UInt(xlen.W))
  })

  // Route hart commands to the appropriate hart
  for (i <- 0 until numHarts) {
    // Default values
    io.harts(i).halt.valid := false.B
    io.harts(i).halt.bits := false.B
    io.harts(i).breakpoint.valid := false.B
    io.harts(i).breakpoint.bits := DontCare
    io.harts(i).register.valid := false.B
    io.harts(i).register.bits := DontCare

    // Override with input if targeting this hart
    when(io.hart_in.id.valid && io.hart_in.id.bits === i.U) {
      io.harts(i) := io.hart_in.bits
    }
  }

  // Connect register results from CPU
  io.reg_res.valid := io.cpuRegData.valid
  io.reg_res.bits := io.cpuRegData.bits

  // Memory interface
  io.mem_in.ready := true.B // Always ready for now

  // Default outputs
  io.imem_iface.req.valid := false.B
  io.imem_iface.req.bits := DontCare
  io.imem_iface.resp.ready := true.B

  io.dmem_iface.req.valid := false.B
  io.dmem_iface.req.bits := DontCare
  io.dmem_iface.resp.ready := true.B

  io.mem_res.valid := false.B
  io.mem_res.bits := 0.U
}
