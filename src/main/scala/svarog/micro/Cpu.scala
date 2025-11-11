package svarog.micro

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.bits.RegFile
import svarog.soc.SvarogConfig
import svarog.memory.{MemoryRequest, MemoryIO}

class CpuIO(xlen: Int) extends Bundle {
  val instmem = new MemoryIO(xlen, xlen)
  val datamem = new MemoryIO(xlen, xlen)
  val halt = Input(Bool()) // Hold pipeline while external ROM is programmed
  val tohostAddr = Input(UInt(xlen.W))
}

object CpuDebugTap {
  def regWrite(id: String): String = s"${id}_debug_regWrite"
  def writeAddr(id: String): String = s"${id}_debug_writeAddr"
  def writeData(id: String): String = s"${id}_debug_writeData"
  def pc(id: String): String = s"${id}_debug_pc"
  def branchValid(id: String): String = s"${id}_debug_branchValid"
  def branchRs1(id: String): String = s"${id}_debug_branchRs1"
  def branchRs2(id: String): String = s"${id}_debug_branchRs2"
  def branchTaken(id: String): String = s"${id}_debug_branchTaken"
  def branchPc(id: String): String = s"${id}_debug_branchPc"
  def flush(id: String): String = s"${id}_debug_flush"
  def bootHold(id: String): String = s"${id}_debug_bootHold"
  def decodeValid(id: String): String = s"${id}_debug_decodeValid"
  def decodeRegWrite(id: String): String = s"${id}_debug_decodeRegWrite"
  def instruction(id: String): String = s"${id}_debug_instruction"
  // ALU debug taps
  def aluValid(id: String): String = s"${id}_debug_aluValid"
  def aluPc(id: String): String = s"${id}_debug_aluPc"
  def aluOp(id: String): String = s"${id}_debug_aluOp"
  def aluHasImm(id: String): String = s"${id}_debug_aluHasImm"
  def aluRs1(id: String): String = s"${id}_debug_aluRs1"
  def aluRs2(id: String): String = s"${id}_debug_aluRs2"
  def aluRd(id: String): String = s"${id}_debug_aluRd"
  def aluInput1(id: String): String = s"${id}_debug_aluInput1"
  def aluInput2(id: String): String = s"${id}_debug_aluInput2"
  def aluOutput(id: String): String = s"${id}_debug_aluOutput"
  def aluRegWrite(id: String): String = s"${id}_debug_aluRegWrite"
  def memRegWrite(id: String): String = s"${id}_debug_memRegWrite"
  def memWbRegWrite(id: String): String = s"${id}_debug_memWbRegWrite"
  def memPc(id: String): String = s"${id}_debug_memPc"
  def memRd(id: String): String = s"${id}_debug_memRd"
  def wbRegWrite(id: String): String = s"${id}_debug_wbRegWrite"
  def wbRd(id: String): String = s"${id}_debug_wbRd"
  def wbPc(id: String): String = s"${id}_debug_wbPc"
}

class Cpu(
    config: SvarogConfig,
    regfileProbeId: Option[String] = None,
    debugTapId: Option[String] = None,
    resetVector: BigInt = 0
) extends Module {
  val io = IO(new CpuIO(config.xlen))

  // Instantiate all pipeline stages
  val fetch = Module(new Fetch(config.xlen, resetVector))
  val decode = Module(new Decode(config.xlen))
  val decodeExecuteReg = Module(new DecodeExecuteStage(config.xlen))
  val regFile = Module(new RegFile(config.xlen, regfileProbeId))
  val execute = Module(new Execute(config.xlen))
  val executeMemoryReg = Module(new ExecuteMemoryStage(config.xlen))
  val memory = Module(new Memory(config.xlen))
  val memoryWritebackReg = Module(new MemoryWritebackStage(config.xlen))
  val writeback = Module(new Writeback(config.xlen))
  val hazardUnit = Module(new HazardUnit)
  val fetchDecodeBuf = Module(new FetchDecodeBuffer(config.xlen))

  val stall_pipeline = Wire(Bool())
  val flush_pipeline = Wire(Bool())
  val insert_bubble = Wire(Bool())
  val memory_stall = Wire(Bool())

  flush_pipeline := execute.io.branchTaken // Flush when branch/jump is taken
  memory_stall := memory.io.stall

  // ===== FETCH STAGE =====
  val decodeHold = stall_pipeline || io.halt
  val frontEndStall = Wire(Bool())

  fetch.io.stall := frontEndStall
  fetch.io.flush := flush_pipeline
  fetch.io.branch_target := execute.io.targetPC
  fetch.io.branch_taken := execute.io.branchTaken
  fetch.io.mem <> io.instmem

  // ===== FETCH -> DECODE REGISTER =====
  fetchDecodeBuf.io.enq_pc := fetch.io.pc_out
  fetchDecodeBuf.io.enq_instruction := fetch.io.instruction
  fetchDecodeBuf.io.enq_valid := fetch.io.valid
  fetchDecodeBuf.io.deq_ready := !decodeHold
  fetchDecodeBuf.io.flush := flush_pipeline || io.halt
  val fetchBufferReady = fetchDecodeBuf.io.enq_ready
  frontEndStall := decodeHold || !fetchBufferReady

  // ===== DECODE STAGE =====
  decode.io.instruction := fetchDecodeBuf.io.deq_instruction
  decode.io.cur_pc := fetchDecodeBuf.io.deq_pc
  decode.io.valid := fetchDecodeBuf.io.deq_valid
  decode.io.stall := frontEndStall

  // ===== HAZARD DETECTION =====
  // Check if current instruction in Decode has data dependencies
  hazardUnit.io.decode_rs1 := decode.io.uop.rs1
  hazardUnit.io.decode_rs2 := decode.io.uop.rs2
  // Instructions that use rs2:
  // - R-type ALU (no immediate)
  // - STORE (writes rs2 to memory)
  // - BRANCH (compares rs1 and rs2)
  val decodeUsesRs2 = (!decode.io.uop.hasImm) ||
    (decode.io.uop.opType === OpType.STORE) ||
    (decode.io.uop.opType === OpType.BRANCH)
  hazardUnit.io.decode_usesRs2 := decodeUsesRs2
  hazardUnit.io.decode_valid := decode.io.uop.valid

  // Check against instruction in Execute stage (use D/E register output)
  hazardUnit.io.execute_rd := decodeExecuteReg.io.execute_uop.rd
  hazardUnit.io.execute_regWrite := decodeExecuteReg.io.execute_uop.regWrite
  hazardUnit.io.execute_valid := decodeExecuteReg.io.execute_uop.valid

  // Check against instruction in Memory stage
  hazardUnit.io.memory_rd := executeMemoryReg.io.memory_rd
  hazardUnit.io.memory_regWrite := executeMemoryReg.io.memory_regWrite
  hazardUnit.io.memory_valid := executeMemoryReg.io.memory_opType =/= OpType.NOP
  hazardUnit.io.writeback_rd := memoryWritebackReg.io.writeback_rd
  hazardUnit.io.writeback_regWrite := memoryWritebackReg.io.writeback_regWrite
  hazardUnit.io.writeback_valid := memoryWritebackReg.io.writeback_opType =/= OpType.NOP

  // Scoreboard hazard detection
  // Hazard unit outputs - combine with memory stall
  stall_pipeline := hazardUnit.io.stall || memory_stall
  insert_bubble := hazardUnit.io.bubble

  // ===== DECODE -> EXECUTE REGISTER =====
  // When stalling due to hazard:
  // - Fetch and Decode are stalled (don't advance - hold the hazarded instruction)
  // - D/E register is NOT stalled (let the instruction producing the value advance)
  // - D/E register is flushed (insert bubble to prevent re-execution)
  // After a few cycles, the hazard clears, and the stalled instruction can advance
  val bubbleUop = WireInit(0.U.asTypeOf(new DecoderUOp(config.xlen)))
  bubbleUop.valid := false.B
  bubbleUop.regWrite := false.B
  bubbleUop.opType := OpType.NOP
  bubbleUop.pc := 0.U

  decodeExecuteReg.io.decode_uop := Mux(insert_bubble, bubbleUop, decode.io.uop)
  decodeExecuteReg.io.stall := memory_stall
  decodeExecuteReg.io.flush := flush_pipeline || io.halt // Flush when branch or boot hold

  // ===== EXECUTE STAGE =====
  execute.io.uop := decodeExecuteReg.io.execute_uop

  // Connect Execute to RegFile read ports
  regFile.readIo.readAddr1 := execute.io.regFile.readAddr1
  regFile.readIo.readAddr2 := execute.io.regFile.readAddr2
  val forwardRs1 = WireDefault(regFile.readIo.readData1)
  val forwardRs2 = WireDefault(regFile.readIo.readData2)
  val exeRs1 = decodeExecuteReg.io.execute_uop.rs1
  val exeRs2 = decodeExecuteReg.io.execute_uop.rs2
  val memForwardValid = executeMemoryReg.io.memory_regWrite &&
    (executeMemoryReg.io.memory_rd =/= 0.U) &&
    (executeMemoryReg.io.memory_opType =/= OpType.NOP) &&
    (executeMemoryReg.io.memory_opType =/= OpType.LOAD)
  val wbForwardValid = memoryWritebackReg.io.writeback_regWrite &&
    (memoryWritebackReg.io.writeback_rd =/= 0.U) &&
    (memoryWritebackReg.io.writeback_opType =/= OpType.NOP)

  when(memForwardValid && (executeMemoryReg.io.memory_rd === exeRs1)) {
    forwardRs1 := executeMemoryReg.io.memory_intResult
  }.elsewhen(
    wbForwardValid && (memoryWritebackReg.io.writeback_rd === exeRs1)
  ) {
    forwardRs1 := memoryWritebackReg.io.writeback_result
  }

  when(memForwardValid && (executeMemoryReg.io.memory_rd === exeRs2)) {
    forwardRs2 := executeMemoryReg.io.memory_intResult
  }.elsewhen(
    wbForwardValid && (memoryWritebackReg.io.writeback_rd === exeRs2)
  ) {
    forwardRs2 := memoryWritebackReg.io.writeback_result
  }

  execute.io.regFile.readData1 := forwardRs1
  execute.io.regFile.readData2 := forwardRs2

  // ===== EXECUTE -> MEMORY REGISTER =====
  executeMemoryReg.io.execute_opType := execute.io.opType
  executeMemoryReg.io.execute_rd := execute.io.rd
  executeMemoryReg.io.execute_regWrite := execute.io.regWrite
  executeMemoryReg.io.execute_intResult := execute.io.intResult
  executeMemoryReg.io.execute_memAddress := execute.io.memAddress
  executeMemoryReg.io.execute_memWidth := execute.io.memWidth
  executeMemoryReg.io.execute_memUnsigned := execute.io.memUnsigned
  executeMemoryReg.io.execute_storeData := execute.io.storeData
  executeMemoryReg.io.execute_pc := decodeExecuteReg.io.execute_uop.pc
  executeMemoryReg.io.execute_isEcall := decodeExecuteReg.io.execute_uop.isEcall
  executeMemoryReg.io.stall := memory_stall
  executeMemoryReg.io.flush := false.B // Don't flush - let branch/jump instruction complete

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
  memory.mem <> io.datamem

  // ===== MEMORY -> WRITEBACK REGISTER =====
  memoryWritebackReg.io.memory_opType := memory.io.wbOpType
  memoryWritebackReg.io.memory_rd := memory.io.wbRd
  memoryWritebackReg.io.memory_regWrite := memory.io.wbRegWrite
  memoryWritebackReg.io.memory_result := memory.io.wbResult
  memoryWritebackReg.io.memory_pc := executeMemoryReg.io.memory_pc
  memoryWritebackReg.io.memory_isEcall := executeMemoryReg.io.memory_isEcall
  memoryWritebackReg.io.stall := memory_stall
  memoryWritebackReg.io.flush := false.B // Don't flush - let branch/jump instruction complete

  // ===== WRITEBACK STAGE =====
  writeback.io.opType := memoryWritebackReg.io.writeback_opType
  writeback.io.rd := memoryWritebackReg.io.writeback_rd
  writeback.io.regWrite := memoryWritebackReg.io.writeback_regWrite
  writeback.io.result := memoryWritebackReg.io.writeback_result

  // Connect Writeback to RegFile write port
  regFile.writeIo.writeEn := writeback.io.regFile.writeEn
  regFile.writeIo.writeAddr := writeback.io.regFile.writeAddr
  regFile.writeIo.writeData := writeback.io.regFile.writeData

  val ecallWritePending = RegInit(0.U(2.W))
  val ecallWriteIndex = RegInit(0.U(2.W))
  val startEcallWrites = memoryWritebackReg.io.writeback_isEcall && (io.tohostAddr =/= 0.U)

  when(startEcallWrites) {
    ecallWritePending := 2.U
    ecallWriteIndex := 0.U
  }.elsewhen(ecallWritePending =/= 0.U) {
    ecallWritePending := ecallWritePending - 1.U
    ecallWriteIndex := ecallWriteIndex + 1.U
  }

  val extraWriteEn = ecallWritePending =/= 0.U
  val extraWriteAddr = WireDefault(0.U(5.W))
  val extraWriteData = WireDefault(0.U(config.xlen.W))
  when(ecallWritePending =/= 0.U) {
    when(ecallWriteIndex === 0.U) {
      extraWriteAddr := 30.U
      extraWriteData := io.tohostAddr + "h3c".U
    }.otherwise {
      extraWriteAddr := 31.U
      extraWriteData := 8.U
    }
  }
  regFile.extraWriteEn := extraWriteEn
  regFile.extraWriteAddr := extraWriteAddr
  regFile.extraWriteData := extraWriteData

  debugTapId.foreach { id =>
    BoringUtils.addSource(
      writeback.io.regFile.writeEn,
      CpuDebugTap.regWrite(id)
    )
    BoringUtils.addSource(
      writeback.io.regFile.writeAddr,
      CpuDebugTap.writeAddr(id)
    )
    BoringUtils.addSource(
      writeback.io.regFile.writeData,
      CpuDebugTap.writeData(id)
    )
    BoringUtils.addSource(
      memoryWritebackReg.io.writeback_pc,
      CpuDebugTap.pc(id)
    )
    val branchValid = execute.io.uop.opType === OpType.BRANCH
    val branchRs1Val = forwardRs1
    val branchRs2Val = forwardRs2
    val branchPc = decodeExecuteReg.io.execute_uop.pc
    val branchValidWire = Wire(Bool())
    branchValidWire := branchValid
    val branchRs1Wire = Wire(UInt(config.xlen.W))
    branchRs1Wire := branchRs1Val
    val branchRs2Wire = Wire(UInt(config.xlen.W))
    branchRs2Wire := branchRs2Val
    val branchTakenWire = Wire(Bool())
    branchTakenWire := execute.io.branchTaken
    val branchPcWire = Wire(UInt(config.xlen.W))
    branchPcWire := branchPc
    BoringUtils.addSource(branchValidWire, CpuDebugTap.branchValid(id))
    BoringUtils.addSource(branchRs1Wire, CpuDebugTap.branchRs1(id))
    BoringUtils.addSource(branchRs2Wire, CpuDebugTap.branchRs2(id))
    BoringUtils.addSource(branchTakenWire, CpuDebugTap.branchTaken(id))
    BoringUtils.addSource(branchPcWire, CpuDebugTap.branchPc(id))

    // Additional debug signals for pipeline flush diagnosis
    val flushWire = Wire(Bool())
    flushWire := flush_pipeline || io.halt
    BoringUtils.addSource(flushWire, CpuDebugTap.flush(id))

    val haltWire = Wire(Bool())
    haltWire := io.halt
    BoringUtils.addSource(haltWire, CpuDebugTap.bootHold(id))

    val decodeValidWire = Wire(Bool())
    decodeValidWire := decode.io.uop.valid
    BoringUtils.addSource(decodeValidWire, CpuDebugTap.decodeValid(id))

    val decodeRegWriteWire = Wire(Bool())
    decodeRegWriteWire := decode.io.uop.regWrite
    BoringUtils.addSource(decodeRegWriteWire, CpuDebugTap.decodeRegWrite(id))

    val instructionWire = Wire(UInt(32.W))
    instructionWire := decode.io.instruction
    BoringUtils.addSource(instructionWire, CpuDebugTap.instruction(id))

    // ALU debug taps - expose Execute stage signals
    val aluValidWire = Wire(Bool())
    aluValidWire := decodeExecuteReg.io.execute_uop.valid && decodeExecuteReg.io.execute_uop.opType === OpType.ALU
    BoringUtils.addSource(aluValidWire, CpuDebugTap.aluValid(id))

    val aluPcWire = Wire(UInt(config.xlen.W))
    aluPcWire := decodeExecuteReg.io.execute_uop.pc
    BoringUtils.addSource(aluPcWire, CpuDebugTap.aluPc(id))

    val aluOpWire = Wire(UInt(4.W))
    aluOpWire := decodeExecuteReg.io.execute_uop.aluOp.asUInt
    BoringUtils.addSource(aluOpWire, CpuDebugTap.aluOp(id))

    val aluHasImmWire = Wire(Bool())
    aluHasImmWire := decodeExecuteReg.io.execute_uop.hasImm
    BoringUtils.addSource(aluHasImmWire, CpuDebugTap.aluHasImm(id))

    val aluRs1Wire = Wire(UInt(5.W))
    aluRs1Wire := decodeExecuteReg.io.execute_uop.rs1
    BoringUtils.addSource(aluRs1Wire, CpuDebugTap.aluRs1(id))

    val aluRs2Wire = Wire(UInt(5.W))
    aluRs2Wire := decodeExecuteReg.io.execute_uop.rs2
    BoringUtils.addSource(aluRs2Wire, CpuDebugTap.aluRs2(id))

    val aluRdWire = Wire(UInt(5.W))
    aluRdWire := decodeExecuteReg.io.execute_uop.rd
    BoringUtils.addSource(aluRdWire, CpuDebugTap.aluRd(id))

    val aluInput1Wire = Wire(UInt(config.xlen.W))
    aluInput1Wire := forwardRs1
    BoringUtils.addSource(aluInput1Wire, CpuDebugTap.aluInput1(id))

    val aluInput2Wire = Wire(UInt(config.xlen.W))
    // Tap the ACTUAL ALU input2 (after Mux that selects imm vs rs2)
    aluInput2Wire := Mux(decodeExecuteReg.io.execute_uop.hasImm, decodeExecuteReg.io.execute_uop.imm, forwardRs2)
    BoringUtils.addSource(aluInput2Wire, CpuDebugTap.aluInput2(id))

    val aluOutputWire = Wire(UInt(config.xlen.W))
    aluOutputWire := execute.io.intResult
    BoringUtils.addSource(aluOutputWire, CpuDebugTap.aluOutput(id))

    val aluRegWriteWire = Wire(Bool())
    aluRegWriteWire := decodeExecuteReg.io.execute_uop.regWrite
    BoringUtils.addSource(aluRegWriteWire, CpuDebugTap.aluRegWrite(id))

    val memRegWriteWire = Wire(Bool())
    memRegWriteWire := executeMemoryReg.io.memory_regWrite
    BoringUtils.addSource(memRegWriteWire, CpuDebugTap.memRegWrite(id))

    val memWbRegWriteWire = Wire(Bool())
    memWbRegWriteWire := memory.io.wbRegWrite
    BoringUtils.addSource(memWbRegWriteWire, CpuDebugTap.memWbRegWrite(id))

    val memPcWire = Wire(UInt(config.xlen.W))
    memPcWire := executeMemoryReg.io.memory_pc
    BoringUtils.addSource(memPcWire, CpuDebugTap.memPc(id))

    val memRdWire = Wire(UInt(5.W))
    memRdWire := executeMemoryReg.io.memory_rd
    BoringUtils.addSource(memRdWire, CpuDebugTap.memRd(id))

    val wbRegWriteWire = Wire(Bool())
    wbRegWriteWire := memoryWritebackReg.io.writeback_regWrite
    BoringUtils.addSource(wbRegWriteWire, CpuDebugTap.wbRegWrite(id))

    val wbRdWire = Wire(UInt(5.W))
    wbRdWire := memoryWritebackReg.io.writeback_rd
    BoringUtils.addSource(wbRdWire, CpuDebugTap.wbRd(id))

    val wbPcWire = Wire(UInt(config.xlen.W))
    wbPcWire := memoryWritebackReg.io.writeback_pc
    BoringUtils.addSource(wbPcWire, CpuDebugTap.wbPc(id))

    // Watchpoint signals (PC = 0x800001b4)
  }
}

class FetchDecodeBuffer(xlen: Int) extends Module {
  val io = IO(new Bundle {
    // Enqueue from Fetch
    val enq_pc = Input(UInt(xlen.W))
    val enq_instruction = Input(UInt(32.W))
    val enq_valid = Input(Bool())
    val enq_ready = Output(Bool())

    // Dequeue to Decode
    val deq_pc = Output(UInt(xlen.W))
    val deq_instruction = Output(UInt(32.W))
    val deq_valid = Output(Bool())
    val deq_ready = Input(Bool())

    // Pipeline control
    val flush = Input(Bool())
  })

  val storedPc = RegInit(0.U(xlen.W))
  val storedInst = RegInit(0.U(32.W))
  val storedValid = RegInit(false.B)

  val outputValid = storedValid || io.enq_valid
  val deqPc = WireDefault(0.U(xlen.W))
  val deqInst = WireDefault(0.U(32.W))

  when(storedValid) {
    deqPc := storedPc
    deqInst := storedInst
  }.elsewhen(io.enq_valid) {
    deqPc := io.enq_pc
    deqInst := io.enq_instruction
  }

  io.deq_valid := outputValid
  io.deq_pc := deqPc
  io.deq_instruction := deqInst

  val consume = io.deq_ready && outputValid
  val consumeStored = storedValid && consume
  val enqueueReady =
    (!storedValid || (io.deq_ready && storedValid)) && !io.flush
  io.enq_ready := enqueueReady

  when(io.flush) {
    storedValid := false.B
  }.elsewhen(consumeStored) {
    when(io.enq_valid && enqueueReady) {
      storedPc := io.enq_pc
      storedInst := io.enq_instruction
      storedValid := true.B
    }.otherwise {
      storedValid := false.B
    }
  }.elsewhen(!storedValid && io.enq_valid && !io.deq_ready) {
    storedPc := io.enq_pc
    storedInst := io.enq_instruction
    storedValid := true.B
  }
}
