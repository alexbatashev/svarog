package svarog.micro

import chisel3._
import chisel3.util._
import svarog.memory.{L1CacheCpuIO, DataCacheIO}
import svarog.bits.RegFile

class CpuIO(xlen: Int) extends Bundle {
  val icache = Flipped(new L1CacheCpuIO(xlen, 32))
  val dcache = new DataCacheIO(xlen)  // CPU produces requests, consumes responses

  // Debug outputs to prevent optimization and observe CPU state
  val debug_regWrite = Output(Bool())           // Is writeback writing to regfile?
  val debug_writeAddr = Output(UInt(5.W))       // Which register is being written?
  val debug_writeData = Output(UInt(xlen.W))    // What value is being written?
  val debug_pc = Output(UInt(xlen.W))           // Current PC from fetch
}

class Cpu(xlen: Int) extends Module {
  val io = IO(new CpuIO(xlen))

  // Instantiate all pipeline stages
  val fetch = Module(new Fetch(xlen))
  val fetchDecodeReg = Module(new FetchDecodeStage(xlen))
  val decode = Module(new Decode(xlen))
  val decodeExecuteReg = Module(new DecodeExecuteStage(xlen))
  val regFile = Module(new RegFile(xlen))
  val execute = Module(new Execute(xlen))
  val executeMemoryReg = Module(new ExecuteMemoryStage(xlen))
  val memory = Module(new Memory(xlen))
  val memoryWritebackReg = Module(new MemoryWritebackStage(xlen))
  val writeback = Module(new Writeback(xlen))
  val hazardUnit = Module(new HazardUnit)

  val stall_pipeline = Wire(Bool())
  val flush_pipeline = Wire(Bool())
  val insert_bubble = Wire(Bool())
  val memory_stall = Wire(Bool())

  flush_pipeline := false.B
  memory_stall := memory.io.stall

  // ===== FETCH STAGE =====
  fetch.io.stall := stall_pipeline
  fetch.io.flush := flush_pipeline
  fetch.io.branch_target := execute.io.targetPC
  fetch.io.branch_taken := execute.io.branchTaken
  fetch.io.icache <> io.icache

  // ===== FETCH -> DECODE REGISTER =====
  fetchDecodeReg.io.fetch_pc := fetch.io.pc_out
  fetchDecodeReg.io.fetch_instruction := fetch.io.instruction
  fetchDecodeReg.io.fetch_valid := fetch.io.valid
  fetchDecodeReg.io.stall := stall_pipeline
  fetchDecodeReg.io.flush := flush_pipeline

  // ===== DECODE STAGE =====
  decode.io.instruction := fetchDecodeReg.io.decode_instruction
  decode.io.cur_pc := fetchDecodeReg.io.decode_pc
  decode.io.valid := fetchDecodeReg.io.decode_valid
  decode.io.stall := stall_pipeline

  // ===== HAZARD DETECTION =====
  // Check if current instruction in Decode has data dependencies
  hazardUnit.io.decode_rs1 := decode.io.uop.rs1
  hazardUnit.io.decode_rs2 := decode.io.uop.rs2
  // Instructions that use rs2:
  // - R-type ALU (no immediate)
  // - STORE (writes rs2 to memory)
  // - BRANCH (compares rs1 and rs2)
  hazardUnit.io.decode_usesRs2 := (!decode.io.uop.hasImm) ||
                                    (decode.io.uop.opType === OpType.STORE) ||
                                    (decode.io.uop.opType === OpType.BRANCH)
  hazardUnit.io.decode_valid := decode.io.uop.valid

  // Check against instruction in Execute stage (use D/E register output)
  hazardUnit.io.execute_rd := decodeExecuteReg.io.execute_uop.rd
  hazardUnit.io.execute_regWrite := decodeExecuteReg.io.execute_uop.regWrite
  hazardUnit.io.execute_valid := decodeExecuteReg.io.execute_uop.valid

  // Check against instruction in Memory stage
  hazardUnit.io.memory_rd := executeMemoryReg.io.memory_rd
  hazardUnit.io.memory_regWrite := executeMemoryReg.io.memory_regWrite
  hazardUnit.io.memory_valid := executeMemoryReg.io.memory_opType =/= OpType.NOP

  // Hazard unit outputs - combine with memory stall
  stall_pipeline := hazardUnit.io.stall || memory_stall
  insert_bubble := hazardUnit.io.bubble

  // ===== DECODE -> EXECUTE REGISTER =====
  // When stalling due to hazard:
  // - Fetch and Decode are stalled (don't advance - hold the hazarded instruction)
  // - D/E register is NOT stalled (let the instruction producing the value advance)
  // - D/E register is flushed (insert bubble to prevent re-execution)
  // After a few cycles, the hazard clears, and the stalled instruction can advance
  decodeExecuteReg.io.decode_uop := decode.io.uop
  decodeExecuteReg.io.stall := false.B  // Never stall D/E register
  decodeExecuteReg.io.flush := flush_pipeline || insert_bubble  // Flush when hazard or branch

  // ===== EXECUTE STAGE =====
  execute.io.uop := decodeExecuteReg.io.execute_uop

  // Connect Execute to RegFile read ports
  regFile.readIo.readAddr1 := execute.io.regFile.readAddr1
  regFile.readIo.readAddr2 := execute.io.regFile.readAddr2
  execute.io.regFile.readData1 := regFile.readIo.readData1
  execute.io.regFile.readData2 := regFile.readIo.readData2

  // ===== EXECUTE -> MEMORY REGISTER =====
  executeMemoryReg.io.execute_opType := execute.io.opType
  executeMemoryReg.io.execute_rd := execute.io.rd
  executeMemoryReg.io.execute_regWrite := execute.io.regWrite
  executeMemoryReg.io.execute_intResult := execute.io.intResult
  executeMemoryReg.io.execute_memAddress := execute.io.memAddress
  executeMemoryReg.io.execute_memWidth := execute.io.memWidth
  executeMemoryReg.io.execute_memUnsigned := execute.io.memUnsigned
  executeMemoryReg.io.execute_storeData := execute.io.storeData
  executeMemoryReg.io.stall := false.B  // Don't stall later stages during hazard
  executeMemoryReg.io.flush := flush_pipeline  // Only flush on branch misprediction

  // ===== MEMORY STAGE =====
  memory.io.opType := executeMemoryReg.io.memory_opType
  memory.io.rd := executeMemoryReg.io.memory_rd
  memory.io.regWrite := executeMemoryReg.io.memory_regWrite
  memory.io.intResult := executeMemoryReg.io.memory_intResult
  memory.io.memAddress := executeMemoryReg.io.memory_memAddress
  memory.io.memWidth := executeMemoryReg.io.memory_memWidth
  memory.io.memUnsigned := executeMemoryReg.io.memory_memUnsigned
  memory.io.storeData := executeMemoryReg.io.memory_storeData

  // Connect data cache - Memory produces requests, CPU forwards to external
  memory.dcache <> io.dcache

  // ===== MEMORY -> WRITEBACK REGISTER =====
  memoryWritebackReg.io.memory_opType := memory.io.wbOpType
  memoryWritebackReg.io.memory_rd := memory.io.wbRd
  memoryWritebackReg.io.memory_regWrite := memory.io.wbRegWrite
  memoryWritebackReg.io.memory_result := memory.io.wbResult
  memoryWritebackReg.io.stall := false.B  // Don't stall later stages during hazard
  memoryWritebackReg.io.flush := flush_pipeline  // Only flush on branch misprediction

  // ===== WRITEBACK STAGE =====
  writeback.io.opType := memoryWritebackReg.io.writeback_opType
  writeback.io.rd := memoryWritebackReg.io.writeback_rd
  writeback.io.regWrite := memoryWritebackReg.io.writeback_regWrite
  writeback.io.result := memoryWritebackReg.io.writeback_result

  // Connect Writeback to RegFile write port
  regFile.writeIo.writeEn := writeback.io.regFile.writeEn
  regFile.writeIo.writeAddr := writeback.io.regFile.writeAddr
  regFile.writeIo.writeData := writeback.io.regFile.writeData

  // ===== DEBUG OUTPUTS =====
  // Wire debug signals to prevent optimization and enable testing
  io.debug_regWrite := writeback.io.regFile.writeEn
  io.debug_writeAddr := writeback.io.regFile.writeAddr
  io.debug_writeData := writeback.io.regFile.writeData
  io.debug_pc := fetch.io.pc_out
}

class FetchDecodeReg(xlen: Int) extends Bundle {
  val pc = UInt(xlen.W)
  val instruction = UInt(32.W)
  val valid = Bool()
}

class FetchDecodeStage(xlen: Int) extends Module {
  val io = IO(new Bundle {
    // From Fetch stage
    val fetch_pc = Input(UInt(xlen.W))
    val fetch_instruction = Input(UInt(32.W))
    val fetch_valid = Input(Bool())

    // To Decode stage
    val decode_pc = Output(UInt(xlen.W))
    val decode_instruction = Output(UInt(32.W))
    val decode_valid = Output(Bool())

    // Pipeline control
    val stall = Input(Bool())
    val flush = Input(Bool())
  })

  val reg = RegInit(0.U.asTypeOf(new FetchDecodeReg(xlen)))

  when(io.flush) {
    reg.valid := false.B
    reg.pc := 0.U
    reg.instruction := 0.U
  }.elsewhen(!io.stall) {
    reg.pc := io.fetch_pc
    reg.instruction := io.fetch_instruction
    reg.valid := io.fetch_valid
  }

  io.decode_pc := reg.pc
  io.decode_instruction := reg.instruction
  io.decode_valid := reg.valid
}

