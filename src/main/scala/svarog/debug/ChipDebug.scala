package svarog.debug

import chisel3._
import chisel3.util._
import svarog.memory.MemWidth
import svarog.memory.MemoryRequest
import svarog.memory.MemoryIO
import svarog.bits.MemoryUtils

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
    val cpuHalted = Input(Vec(numHarts, Bool())) // Status: is CPU currently halted?

    val imem_iface = new MemoryIO(xlen, xlen)
    val dmem_iface = new MemoryIO(xlen, xlen)

    val mem_res = Decoupled(UInt(xlen.W))
    val reg_res = Decoupled(UInt(xlen.W))
    val halted = Output(Vec(numHarts, Bool())) // Status output: which harts are halted
  })

  // Route hart commands to the appropriate hart
  for (i <- 0 until numHarts) {
    // Default: no commands
    val hartSelected = io.hart_in.id.valid && io.hart_in.id.bits === i.U

    io.harts(i).halt.valid := Mux(hartSelected, io.hart_in.bits.halt.valid, false.B)
    io.harts(i).halt.bits := Mux(hartSelected, io.hart_in.bits.halt.bits, false.B)

    io.harts(i).breakpoint.valid := Mux(hartSelected, io.hart_in.bits.breakpoint.valid, false.B)
    io.harts(i).breakpoint.bits := Mux(hartSelected, io.hart_in.bits.breakpoint.bits, DontCare)

    io.harts(i).watchpoint.valid := Mux(hartSelected, io.hart_in.bits.watchpoint.valid, false.B)
    io.harts(i).watchpoint.bits := Mux(hartSelected, io.hart_in.bits.watchpoint.bits, DontCare)

    io.harts(i).register.valid := Mux(hartSelected, io.hart_in.bits.register.valid, false.B)
    io.harts(i).register.bits := Mux(hartSelected, io.hart_in.bits.register.bits, DontCare)

    io.harts(i).setPC.valid := Mux(hartSelected, io.hart_in.bits.setPC.valid, false.B)
    io.harts(i).setPC.bits := Mux(hartSelected, io.hart_in.bits.setPC.bits, DontCare)

    // Pass through halt status
    io.halted(i) := io.cpuHalted(i)
  }

  // Connect register results from CPU
  io.reg_res.valid := io.cpuRegData.valid
  io.reg_res.bits := io.cpuRegData.bits

  // Memory interface - route debug memory requests to imem or dmem
  val memPending = RegInit(false.B)
  val memIsInstr = RegInit(false.B)
  val memWordOffset = RegInit(0.U(log2Ceil(xlen / 8).W))
  val memReqWidth = RegInit(MemWidth.WORD)

  // Accept new memory requests when not pending
  io.mem_in.ready := !memPending

  // Convert debug memory request to MemoryRequest format
  val memReqBits = Wire(new MemoryRequest(xlen, xlen))

  val wordSize = xlen / 8

  // Compute word-aligned address and byte offset
  val byteAddr = io.mem_in.bits.addr
  val (wordAlignedAddr, wordOffset) = MemoryUtils.alignAddress(byteAddr, wordSize)

  memReqBits.address := wordAlignedAddr
  memReqBits.write := io.mem_in.bits.write

  // Generate shifted mask
  val shiftedMask = MemoryUtils.generateShiftedMask(io.mem_in.bits.reqWidth, wordOffset, xlen)
  memReqBits.mask := shiftedMask

  // Convert scalar data to Vec of bytes and shift based on byte offset
  val writeDataBytes = Wire(Vec(wordSize, UInt(8.W)))
  for (i <- 0 until wordSize) {
    writeDataBytes(i) := io.mem_in.bits.data((i + 1) * 8 - 1, i * 8)
  }

  val shiftedWriteData = MemoryUtils.shiftWriteData(writeDataBytes, wordOffset, wordSize)
  memReqBits.dataWrite := shiftedWriteData

  // Route to instruction or data memory based on 'instr' bit
  io.imem_iface.req.valid := io.mem_in.valid && io.mem_in.bits.instr && !memPending
  io.imem_iface.req.bits := memReqBits

  io.dmem_iface.req.valid := io.mem_in.valid && !io.mem_in.bits.instr && !memPending
  io.dmem_iface.req.bits := memReqBits

  // Track pending requests
  when(io.mem_in.valid && io.mem_in.ready) {
    memPending := true.B
    memIsInstr := io.mem_in.bits.instr
    memWordOffset := wordOffset
    memReqWidth := io.mem_in.bits.reqWidth
  }

  // Handle responses
  io.imem_iface.resp.ready := memPending && memIsInstr
  io.dmem_iface.resp.ready := memPending && !memIsInstr

  // Get raw response data
  val rawRespBytes = Mux(
    memIsInstr,
    io.imem_iface.resp.bits.dataRead,
    io.dmem_iface.resp.bits.dataRead
  )

  // Unshift read data back based on byte offset
  val shiftedRespBytes = MemoryUtils.unshiftReadData(rawRespBytes, memWordOffset, wordSize)

  // Convert to scalar
  val respData = shiftedRespBytes.asUInt

  io.mem_res.valid := (io.imem_iface.resp.valid && memIsInstr) ||
    (io.dmem_iface.resp.valid && !memIsInstr)
  io.mem_res.bits := respData

  // Clear pending when response is consumed
  when(io.mem_res.valid && io.mem_res.ready) {
    memPending := false.B
  }
}
