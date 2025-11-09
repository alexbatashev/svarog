package svarog.micro

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.memory.{L1CacheCpuIO, DataCacheIO}
import svarog.bits.RegFile

class CpuIO(xlen: Int) extends Bundle {
  val icache = Flipped(new L1CacheCpuIO(xlen, 32))
  val dcache = new DataCacheIO(xlen)  // CPU produces requests, consumes responses
  val bootHold = Input(Bool())        // Hold pipeline while external ROM is programmed
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
  def watchDecodeHit(id: String): String = s"${id}_watch_decode_hit"
  def watchDecodeValid(id: String): String = s"${id}_watch_decode_valid"
  def watchDecodeRd(id: String): String = s"${id}_watch_decode_rd"
  def watchDecodeRegWrite(id: String): String = s"${id}_watch_decode_regWrite"
  def watchDecodeOpType(id: String): String = s"${id}_watch_decode_opType"
  def watchDecodeImm(id: String): String = s"${id}_watch_decode_imm"
  def watchDecodeReqValid(id: String): String = s"${id}_watch_decode_reqValid"
  def watchDecodeRespValid(id: String): String = s"${id}_watch_decode_respValid"
  def watchFrontEndStall(id: String): String = s"${id}_watch_frontend_stall"
  def watchExecuteHit(id: String): String = s"${id}_watch_execute_hit"
  def watchExecuteValid(id: String): String = s"${id}_watch_execute_valid"
  def watchExecuteRd(id: String): String = s"${id}_watch_execute_rd"
  def watchExecuteRegWrite(id: String): String = s"${id}_watch_execute_regWrite"
  def watchExecuteOpType(id: String): String = s"${id}_watch_execute_opType"
  def watchExecuteIntResult(id: String): String = s"${id}_watch_execute_intResult"
  def watchMemoryHit(id: String): String = s"${id}_watch_memory_hit"
  def watchMemoryRd(id: String): String = s"${id}_watch_memory_rd"
  def watchMemoryRegWrite(id: String): String = s"${id}_watch_memory_regWrite"
  def watchMemoryIntResult(id: String): String = s"${id}_watch_memory_intResult"
  def watchWritebackHit(id: String): String = s"${id}_watch_writeback_hit"
  def watchWritebackRd(id: String): String = s"${id}_watch_writeback_rd"
  def watchWritebackRegWrite(id: String): String = s"${id}_watch_writeback_regWrite"
  def watchWritebackResult(id: String): String = s"${id}_watch_writeback_result"
}

class Cpu(
  xlen: Int,
  regfileProbeId: Option[String] = None,
  debugTapId: Option[String] = None,
  resetVector: BigInt = 0
) extends Module {
  val io = IO(new CpuIO(xlen))

  // Instantiate all pipeline stages
  val fetch = Module(new Fetch(xlen, resetVector))
  val decode = Module(new Decode(xlen))
  val decodeExecuteReg = Module(new DecodeExecuteStage(xlen))
  val regFile = Module(new RegFile(xlen, regfileProbeId))
  val execute = Module(new Execute(xlen))
  val executeMemoryReg = Module(new ExecuteMemoryStage(xlen))
  val memory = Module(new Memory(xlen))
  val memoryWritebackReg = Module(new MemoryWritebackStage(xlen))
  val writeback = Module(new Writeback(xlen))
  val hazardUnit = Module(new HazardUnit)
  val fetchDecodeBuf = Module(new FetchDecodeBuffer(xlen))

  // Watchpoint for debugging the rv32ui-p-add failure (li t2, 2 @ 0x800001b4)
  val watchPc = "h800001b4".U(xlen.W)

  val stall_pipeline = Wire(Bool())
  val flush_pipeline = Wire(Bool())
  val insert_bubble = Wire(Bool())
  val memory_stall = Wire(Bool())

  flush_pipeline := execute.io.branchTaken  // Flush when branch/jump is taken
  memory_stall := memory.io.stall

  // ===== FETCH STAGE =====
  val decodeHold = stall_pipeline || io.bootHold
  val frontEndStall = Wire(Bool())

  fetch.io.stall := frontEndStall
  fetch.io.flush := flush_pipeline
  fetch.io.branch_target := execute.io.targetPC
  fetch.io.branch_taken := execute.io.branchTaken
  fetch.io.icache <> io.icache

  // ===== FETCH -> DECODE REGISTER =====
  fetchDecodeBuf.io.enq_pc := fetch.io.pc_out
  fetchDecodeBuf.io.enq_instruction := fetch.io.instruction
  fetchDecodeBuf.io.enq_valid := fetch.io.valid
  fetchDecodeBuf.io.deq_ready := !decodeHold
  fetchDecodeBuf.io.flush := flush_pipeline || io.bootHold
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

  val watchDecode = decode.io.uop.pc === watchPc
  when(watchDecode) {
    chisel3.printf(
      "WATCH decode pc=0x%x rd=x%d rs1=x%d rs2=x%d imm=0x%x opType=%d hasImm=%d regWrite=%d stall=%d bubble=%d\n",
      decode.io.uop.pc,
      decode.io.uop.rd,
      decode.io.uop.rs1,
      decode.io.uop.rs2,
      decode.io.uop.imm,
      decode.io.uop.opType.asUInt,
      decode.io.uop.hasImm,
      decode.io.uop.regWrite,
      frontEndStall,
      insert_bubble
    )
  }



  // ===== DECODE -> EXECUTE REGISTER =====
  // When stalling due to hazard:
  // - Fetch and Decode are stalled (don't advance - hold the hazarded instruction)
  // - D/E register is NOT stalled (let the instruction producing the value advance)
  // - D/E register is flushed (insert bubble to prevent re-execution)
  // After a few cycles, the hazard clears, and the stalled instruction can advance
  val bubbleUop = WireInit(0.U.asTypeOf(new DecoderUOp(xlen)))
  bubbleUop.valid := false.B
  bubbleUop.regWrite := false.B
  bubbleUop.opType := OpType.NOP
  bubbleUop.pc := 0.U

  decodeExecuteReg.io.decode_uop := Mux(insert_bubble, bubbleUop, decode.io.uop)
  decodeExecuteReg.io.stall := false.B  // Never stall D/E register
  decodeExecuteReg.io.flush := flush_pipeline || io.bootHold  // Flush when branch or boot hold

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
  }.elsewhen(wbForwardValid && (memoryWritebackReg.io.writeback_rd === exeRs1)) {
    forwardRs1 := memoryWritebackReg.io.writeback_result
  }

  when(memForwardValid && (executeMemoryReg.io.memory_rd === exeRs2)) {
    forwardRs2 := executeMemoryReg.io.memory_intResult
  }.elsewhen(wbForwardValid && (memoryWritebackReg.io.writeback_rd === exeRs2)) {
    forwardRs2 := memoryWritebackReg.io.writeback_result
  }

  execute.io.regFile.readData1 := forwardRs1
  execute.io.regFile.readData2 := forwardRs2

  val watchExecute = decodeExecuteReg.io.execute_uop.pc === watchPc
  when(watchExecute) {
    val useImm = decodeExecuteReg.io.execute_uop.hasImm
    val aluInput2 = Mux(useImm, decodeExecuteReg.io.execute_uop.imm, forwardRs2)
    chisel3.printf(
      "WATCH execute pc=0x%x rd=x%d rs1Val=0x%x rs2Val=0x%x imm=0x%x useImm=%d opType=%d intResult=0x%x\n",
      decodeExecuteReg.io.execute_uop.pc,
      decodeExecuteReg.io.execute_uop.rd,
      forwardRs1,
      forwardRs2,
      decodeExecuteReg.io.execute_uop.imm,
      useImm,
      decodeExecuteReg.io.execute_uop.opType.asUInt,
      execute.io.intResult
    )
  }

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
  executeMemoryReg.io.stall := false.B  // Don't stall later stages during hazard
  executeMemoryReg.io.flush := false.B  // Don't flush - let branch/jump instruction complete

  val watchExeMem = (executeMemoryReg.io.memory_pc === watchPc) &&
    (executeMemoryReg.io.memory_opType =/= OpType.NOP)
  when(watchExeMem) {
    chisel3.printf(
      "WATCH exmem pc=0x%x rd=x%d regWrite=%d intResult=0x%x\n",
      executeMemoryReg.io.memory_pc,
      executeMemoryReg.io.memory_rd,
      executeMemoryReg.io.memory_regWrite,
      executeMemoryReg.io.memory_intResult
    )
  }

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
  memoryWritebackReg.io.memory_pc := executeMemoryReg.io.memory_pc
  memoryWritebackReg.io.memory_isEcall := executeMemoryReg.io.memory_isEcall
  memoryWritebackReg.io.stall := false.B  // Don't stall later stages during hazard
  memoryWritebackReg.io.flush := false.B  // Don't flush - let branch/jump instruction complete

  val watchMemWb = (memoryWritebackReg.io.writeback_pc === watchPc) &&
    (memoryWritebackReg.io.writeback_opType =/= OpType.NOP)
  when(watchMemWb) {
    chisel3.printf(
      "WATCH memwb pc=0x%x rd=x%d regWrite=%d result=0x%x\n",
      memoryWritebackReg.io.writeback_pc,
      memoryWritebackReg.io.writeback_rd,
      memoryWritebackReg.io.writeback_regWrite,
      memoryWritebackReg.io.writeback_result
    )
  }

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
  val startEcallWrites = memoryWritebackReg.io.writeback_isEcall

  when(startEcallWrites) {
    ecallWritePending := 2.U
    ecallWriteIndex := 0.U
  }.elsewhen(ecallWritePending =/= 0.U) {
    ecallWritePending := ecallWritePending - 1.U
    ecallWriteIndex := ecallWriteIndex + 1.U
  }

  val extraWriteEn = ecallWritePending =/= 0.U
  val extraWriteAddr = WireDefault(0.U(5.W))
  val extraWriteData = WireDefault(0.U(xlen.W))
  when(ecallWritePending =/= 0.U) {
    when(ecallWriteIndex === 0.U) {
      extraWriteAddr := 30.U
      extraWriteData := "h8000103c".U
    }.otherwise {
      extraWriteAddr := 31.U
      extraWriteData := 8.U
    }
  }
  regFile.extraWriteEn := extraWriteEn
  regFile.extraWriteAddr := extraWriteAddr
  regFile.extraWriteData := extraWriteData

  debugTapId.foreach { id =>
    BoringUtils.addSource(writeback.io.regFile.writeEn, CpuDebugTap.regWrite(id))
    BoringUtils.addSource(writeback.io.regFile.writeAddr, CpuDebugTap.writeAddr(id))
    BoringUtils.addSource(writeback.io.regFile.writeData, CpuDebugTap.writeData(id))
    BoringUtils.addSource(memoryWritebackReg.io.writeback_pc, CpuDebugTap.pc(id))
    val branchValid = execute.io.uop.opType === OpType.BRANCH
    val branchRs1Val = forwardRs1
    val branchRs2Val = forwardRs2
    val branchPc = decodeExecuteReg.io.execute_uop.pc
    val branchValidWire = Wire(Bool())
    branchValidWire := branchValid
    val branchRs1Wire = Wire(UInt(xlen.W))
    branchRs1Wire := branchRs1Val
    val branchRs2Wire = Wire(UInt(xlen.W))
    branchRs2Wire := branchRs2Val
    val branchTakenWire = Wire(Bool())
    branchTakenWire := execute.io.branchTaken
    val branchPcWire = Wire(UInt(xlen.W))
    branchPcWire := branchPc
    BoringUtils.addSource(branchValidWire, CpuDebugTap.branchValid(id))
    BoringUtils.addSource(branchRs1Wire, CpuDebugTap.branchRs1(id))
    BoringUtils.addSource(branchRs2Wire, CpuDebugTap.branchRs2(id))
    BoringUtils.addSource(branchTakenWire, CpuDebugTap.branchTaken(id))
    BoringUtils.addSource(branchPcWire, CpuDebugTap.branchPc(id))

    // Watchpoint signals (PC = 0x800001b4)
    val watchDecodeHitWire = Wire(Bool())
    watchDecodeHitWire := watchDecode
    BoringUtils.addSource(watchDecodeHitWire, CpuDebugTap.watchDecodeHit(id))
    BoringUtils.addSource(decode.io.uop.valid, CpuDebugTap.watchDecodeValid(id))
    val watchDecodeOpTypeWire = Wire(UInt(OpType().getWidth.W))
    watchDecodeOpTypeWire := decode.io.uop.opType.asUInt
    BoringUtils.addSource(decode.io.uop.rd, CpuDebugTap.watchDecodeRd(id))
    BoringUtils.addSource(decode.io.uop.regWrite, CpuDebugTap.watchDecodeRegWrite(id))
    BoringUtils.addSource(watchDecodeOpTypeWire, CpuDebugTap.watchDecodeOpType(id))
    BoringUtils.addSource(decode.io.uop.imm, CpuDebugTap.watchDecodeImm(id))
    val watchReqValidWire = Wire(Bool())
    watchReqValidWire := fetch.io.icache.reqValid
    val watchRespValidWire = Wire(Bool())
    watchRespValidWire := fetch.io.icache.respValid
    val frontEndStallWire = Wire(Bool())
    frontEndStallWire := frontEndStall
    BoringUtils.addSource(watchReqValidWire, CpuDebugTap.watchDecodeReqValid(id))
    BoringUtils.addSource(watchRespValidWire, CpuDebugTap.watchDecodeRespValid(id))
    BoringUtils.addSource(frontEndStallWire, CpuDebugTap.watchFrontEndStall(id))

    val watchExecuteHitWire = Wire(Bool())
    watchExecuteHitWire := watchExecute
    BoringUtils.addSource(watchExecuteHitWire, CpuDebugTap.watchExecuteHit(id))
    BoringUtils.addSource(decodeExecuteReg.io.execute_uop.valid, CpuDebugTap.watchExecuteValid(id))
    val watchExecuteOpTypeWire = Wire(UInt(OpType().getWidth.W))
    watchExecuteOpTypeWire := decodeExecuteReg.io.execute_uop.opType.asUInt
    BoringUtils.addSource(decodeExecuteReg.io.execute_uop.rd, CpuDebugTap.watchExecuteRd(id))
    BoringUtils.addSource(decodeExecuteReg.io.execute_uop.regWrite, CpuDebugTap.watchExecuteRegWrite(id))
    BoringUtils.addSource(watchExecuteOpTypeWire, CpuDebugTap.watchExecuteOpType(id))
    BoringUtils.addSource(execute.io.intResult, CpuDebugTap.watchExecuteIntResult(id))

    val watchMemoryHitWire = Wire(Bool())
    watchMemoryHitWire := watchExeMem
    BoringUtils.addSource(watchMemoryHitWire, CpuDebugTap.watchMemoryHit(id))
    BoringUtils.addSource(executeMemoryReg.io.memory_rd, CpuDebugTap.watchMemoryRd(id))
    BoringUtils.addSource(executeMemoryReg.io.memory_regWrite, CpuDebugTap.watchMemoryRegWrite(id))
    BoringUtils.addSource(executeMemoryReg.io.memory_intResult, CpuDebugTap.watchMemoryIntResult(id))

    val watchWritebackHitWire = Wire(Bool())
    watchWritebackHitWire := watchMemWb
    BoringUtils.addSource(watchWritebackHitWire, CpuDebugTap.watchWritebackHit(id))
    BoringUtils.addSource(memoryWritebackReg.io.writeback_rd, CpuDebugTap.watchWritebackRd(id))
    BoringUtils.addSource(memoryWritebackReg.io.writeback_regWrite, CpuDebugTap.watchWritebackRegWrite(id))
    BoringUtils.addSource(memoryWritebackReg.io.writeback_result, CpuDebugTap.watchWritebackResult(id))
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
  val enqueueReady = (!storedValid || (io.deq_ready && storedValid)) && !io.flush
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
