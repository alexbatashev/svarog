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

    val imem_iface = new MemoryIO(xlen, xlen)
    val dmem_iface = new MemoryIO(xlen, xlen)

    val mem_res = Decoupled(UInt(xlen.W))
  })

  // io.harts.foreach { hart =>
  //   hart := 0.U
  // }

  when(io.hart_in.id.valid) {
    io.harts(0) := io.hart_in.bits
  }

  val memPending = RegInit(false.B)

}
