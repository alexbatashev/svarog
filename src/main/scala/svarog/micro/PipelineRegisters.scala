package svarog.micro

import chisel3._
import chisel3.util._

// Decode -> Execute Pipeline Register
class DecodeExecuteStage(xlen: Int) extends Module {
  val io = IO(new Bundle {
    // From Decode stage
    val decode_uop = Input(new DecoderUOp(xlen))

    // To Execute stage
    val execute_uop = Output(new DecoderUOp(xlen))

    // Pipeline control
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  val reg = RegInit(0.U.asTypeOf(new DecoderUOp(xlen)))

  when(io.flush) {
    // Insert a NOP/bubble
    reg.valid := false.B
    reg.regWrite := false.B
    reg.opType := OpType.NOP
  }.elsewhen(!io.stall) {
    reg := io.decode_uop
  }

  io.execute_uop := reg
}

// Execute -> Memory Pipeline Register
class ExecuteMemoryStage(xlen: Int) extends Module {
  val io = IO(new Bundle {
    // From Execute stage
    val execute_opType = Input(OpType())
    val execute_rd = Input(UInt(5.W))
    val execute_regWrite = Input(Bool())
    val execute_intResult = Input(UInt(xlen.W))
    val execute_memAddress = Input(UInt(xlen.W))
    val execute_memWidth = Input(MemWidth())
    val execute_memUnsigned = Input(Bool())
    val execute_storeData = Input(UInt(xlen.W))

    // To Memory stage
    val memory_opType = Output(OpType())
    val memory_rd = Output(UInt(5.W))
    val memory_regWrite = Output(Bool())
    val memory_intResult = Output(UInt(xlen.W))
    val memory_memAddress = Output(UInt(xlen.W))
    val memory_memWidth = Output(MemWidth())
    val memory_memUnsigned = Output(Bool())
    val memory_storeData = Output(UInt(xlen.W))

    // Pipeline control
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  val reg_opType = RegInit(OpType.NOP)
  val reg_rd = RegInit(0.U(5.W))
  val reg_regWrite = RegInit(false.B)
  val reg_intResult = RegInit(0.U(xlen.W))
  val reg_memAddress = RegInit(0.U(xlen.W))
  val reg_memWidth = RegInit(MemWidth.WORD)
  val reg_memUnsigned = RegInit(false.B)
  val reg_storeData = RegInit(0.U(xlen.W))

  when(io.flush) {
    reg_opType := OpType.NOP
    reg_regWrite := false.B
  }.elsewhen(!io.stall) {
    reg_opType := io.execute_opType
    reg_rd := io.execute_rd
    reg_regWrite := io.execute_regWrite
    reg_intResult := io.execute_intResult
    reg_memAddress := io.execute_memAddress
    reg_memWidth := io.execute_memWidth
    reg_memUnsigned := io.execute_memUnsigned
    reg_storeData := io.execute_storeData
  }

  io.memory_opType := reg_opType
  io.memory_rd := reg_rd
  io.memory_regWrite := reg_regWrite
  io.memory_intResult := reg_intResult
  io.memory_memAddress := reg_memAddress
  io.memory_memWidth := reg_memWidth
  io.memory_memUnsigned := reg_memUnsigned
  io.memory_storeData := reg_storeData
}

// Memory -> Writeback Pipeline Register
class MemoryWritebackStage(xlen: Int) extends Module {
  val io = IO(new Bundle {
    // From Memory stage
    val memory_opType = Input(OpType())
    val memory_rd = Input(UInt(5.W))
    val memory_regWrite = Input(Bool())
    val memory_result = Input(UInt(xlen.W))

    // To Writeback stage
    val writeback_opType = Output(OpType())
    val writeback_rd = Output(UInt(5.W))
    val writeback_regWrite = Output(Bool())
    val writeback_result = Output(UInt(xlen.W))

    // Pipeline control
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  val reg_opType = RegInit(OpType.NOP)
  val reg_rd = RegInit(0.U(5.W))
  val reg_regWrite = RegInit(false.B)
  val reg_result = RegInit(0.U(xlen.W))

  when(io.flush) {
    reg_opType := OpType.NOP
    reg_regWrite := false.B
  }.elsewhen(!io.stall) {
    reg_opType := io.memory_opType
    reg_rd := io.memory_rd
    reg_regWrite := io.memory_regWrite
    reg_result := io.memory_result
  }

  io.writeback_opType := reg_opType
  io.writeback_rd := reg_rd
  io.writeback_regWrite := reg_regWrite
  io.writeback_result := reg_result
}
