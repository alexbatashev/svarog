package svarog.micro

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.bits.RegFile
import svarog.soc.SvarogConfig
import svarog.memory.{MemoryRequest, MemoryIO}
import svarog.decoder.SimpleDecoder
import svarog.decoder.InstWord

class CpuIO(xlen: Int) extends Bundle {
  val instmem = new MemoryIO(xlen, xlen)
  val datamem = new MemoryIO(xlen, xlen)
  val halt = Input(Bool()) // Hold pipeline while external ROM is programmed
  val tohostAddr = Input(UInt(xlen.W))
}

class Cpu(
    config: SvarogConfig,
    regfileProbeId: Option[String] = None,
    resetVector: BigInt = 0
) extends Module {
  // Public interface
  val io = IO(new CpuIO(config.xlen))

  // Memories
  val regFile = Module(new RegFile(config.xlen, regfileProbeId))

  // Stages
  val fetch = Module(new Fetch(config.xlen, resetVector))
  val decode = Module(new SimpleDecoder(config.xlen))
  val execute = Module(new Execute(config.xlen))
  val memory = Module(new Memory(config.xlen))

  // Fetch memory
  fetch.io.mem <> io.instmem

  // Data memory
  memory.mem <> io.datamem

  // IF -> ID
  val fetchDecodeQueue = Module(new Queue(Vec(1, new InstWord(config.xlen)), 1))

  fetchDecodeQueue.io.enq <> fetch.io.inst_out
  decode.io.inst <> fetchDecodeQueue.io.deq

  // ID -> EX
  val decodeExecQueue = Module(new Queue(Vec(1, new InstWord(config.xlen)), 1))
  decodeExecQueue.io.enq <> decode.io.decoded

  // val fetchDecodeBuf = Module(new FetchDecodeBuffer(config.xlen))

  // val execute = Module(new Execute(config.xlen))

  // val decodeExecuteReg = Module(new DecodeExecuteStage(config.xlen))
  // val executeMemoryReg = Module(new ExecuteMemoryStage(config.xlen))
  // val memory = Module(new Memory(config.xlen))
  // val memoryWritebackReg = Module(new MemoryWritebackStage(config.xlen))
  // val writeback = Module(new Writeback(config.xlen))
  // val hazardUnit = Module(new HazardUnit)

  // val stall_pipeline = Wire(Bool())
  // val flush_pipeline = Wire(Bool())
  // val insert_bubble = Wire(Bool())
  // val memory_stall = Wire(Bool())

  // flush_pipeline := execute.io.branchTaken // Flush when branch/jump is taken
  // memory_stall := memory.io.stall

  // // ===== FETCH STAGE =====
  // val decodeHold = stall_pipeline || io.halt
  // val frontEndStall = Wire(Bool())

  // fetch.io.stall := frontEndStall
  // fetch.io.flush := flush_pipeline
  // fetch.io.branch_target := execute.io.targetPC
  // fetch.io.branch_taken := execute.io.branchTaken
  // fetch.io.mem <> io.instmem

  // // ===== FETCH -> DECODE BUFFER =====
  // fetchDecodeBuf.io.enq <> fetch.io.inst_out
  // fetchDecodeBuf.io.flush := flush_pipeline || io.halt

  // val fetchBufferReady = fetchDecodeBuf.io.enq.ready
  // frontEndStall := decodeHold || !fetchBufferReady

  // // ===== BUFFER -> DECODER =====
  // decode.io.inst <> fetchDecodeBuf.io.deq

  // // ===== DECODER -> EXECUTE =====
  // // Decoder output ready signal
  // val pipeline_can_advance = !memory_stall && !insert_bubble
  // decode.io.decoded.ready := pipeline_can_advance

  // // Extract decoded MicroOp
  // val decoded_uop = decode.io.decoded.bits(0)

  // // ===== HAZARD DETECTION =====
  // // Check if current instruction in Decode has data dependencies
  // hazardUnit.io.decode_rs1 := decoded_uop.rs1
  // hazardUnit.io.decode_rs2 := decoded_uop.rs2
  // // Instructions that use rs2:
  // // - R-type ALU (no immediate)
  // // - STORE (writes rs2 to memory)
  // // - BRANCH (compares rs1 and rs2)
  // val decodeUsesRs2 = (!decoded_uop.hasImm) ||
  //   (decoded_uop.opType === svarog.decoder.OpType.STORE) ||
  //   (decoded_uop.opType === svarog.decoder.OpType.BRANCH)
  // hazardUnit.io.decode_usesRs2 := decodeUsesRs2
  // hazardUnit.io.decode_valid := decode.io.decoded.valid && decoded_uop.valid

  // // Check against instruction in Execute stage (use D/E register output)
  // hazardUnit.io.execute_rd := decodeExecuteReg.io.execute_uop.rd
  // hazardUnit.io.execute_regWrite := decodeExecuteReg.io.execute_uop.regWrite
  // hazardUnit.io.execute_valid := decodeExecuteReg.io.execute_uop.valid

  // // Check against instruction in Memory stage
  // hazardUnit.io.memory_rd := executeMemoryReg.io.memory_rd
  // hazardUnit.io.memory_regWrite := executeMemoryReg.io.memory_regWrite
  // hazardUnit.io.memory_valid := executeMemoryReg.io.memory_opType =/= svarog.decoder.OpType.NOP
  // hazardUnit.io.writeback_rd := memoryWritebackReg.io.writeback_rd
  // hazardUnit.io.writeback_regWrite := memoryWritebackReg.io.writeback_regWrite
  // hazardUnit.io.writeback_valid := memoryWritebackReg.io.writeback_opType =/= svarog.decoder.OpType.NOP

  // // Scoreboard hazard detection
  // // Hazard unit outputs - combine with memory stall
  // stall_pipeline := hazardUnit.io.stall || memory_stall
  // insert_bubble := hazardUnit.io.bubble

  // // ===== DECODE -> EXECUTE REGISTER =====
  // // When stalling due to hazard:
  // // - Fetch and Decode are stalled (don't advance - hold the hazarded instruction)
  // // - D/E register is NOT stalled (let the instruction producing the value advance)
  // // - D/E register is flushed (insert bubble to prevent re-execution)
  // // After a few cycles, the hazard clears, and the stalled instruction can advance
  // val bubbleUop = WireDefault(svarog.decoder.MicroOp.getInvalid(config.xlen))
  // bubbleUop.opType := svarog.decoder.OpType.NOP

  // val loadBubble = insert_bubble && !memory_stall
  // val loadDecode =
  //   decode.io.decoded.valid && decode.io.decoded.ready && !insert_bubble
  // val nextDecodeUop =
  //   WireDefault(decodeExecuteReg.io.execute_uop)

  // when(loadBubble) {
  //   nextDecodeUop := bubbleUop
  // }.elsewhen(loadDecode) {
  //   nextDecodeUop := decoded_uop
  // }

  // decodeExecuteReg.io.decode_uop := nextDecodeUop
  // decodeExecuteReg.io.stall := memory_stall || (!loadBubble && !loadDecode)
  // decodeExecuteReg.io.flush := flush_pipeline || io.halt // Flush when branch or boot hold

  // // ===== EXECUTE STAGE =====
  // execute.io.uop := decodeExecuteReg.io.execute_uop

  // // Connect Execute to RegFile read ports
  // regFile.readIo.readAddr1 := execute.io.regFile.readAddr1
  // regFile.readIo.readAddr2 := execute.io.regFile.readAddr2
  // val forwardRs1 = WireDefault(regFile.readIo.readData1)
  // val forwardRs2 = WireDefault(regFile.readIo.readData2)
  // val exeRs1 = decodeExecuteReg.io.execute_uop.rs1
  // val exeRs2 = decodeExecuteReg.io.execute_uop.rs2
  // val memForwardValid = executeMemoryReg.io.memory_regWrite &&
  //   (executeMemoryReg.io.memory_rd =/= 0.U) &&
  //   (executeMemoryReg.io.memory_opType =/= svarog.decoder.OpType.NOP) &&
  //   (executeMemoryReg.io.memory_opType =/= svarog.decoder.OpType.LOAD)
  // val wbForwardValid = memoryWritebackReg.io.writeback_regWrite &&
  //   (memoryWritebackReg.io.writeback_rd =/= 0.U) &&
  //   (memoryWritebackReg.io.writeback_opType =/= svarog.decoder.OpType.NOP)

  // when(memForwardValid && (executeMemoryReg.io.memory_rd === exeRs1)) {
  //   forwardRs1 := executeMemoryReg.io.memory_intResult
  // }.elsewhen(
  //   wbForwardValid && (memoryWritebackReg.io.writeback_rd === exeRs1)
  // ) {
  //   forwardRs1 := memoryWritebackReg.io.writeback_result
  // }

  // when(memForwardValid && (executeMemoryReg.io.memory_rd === exeRs2)) {
  //   forwardRs2 := executeMemoryReg.io.memory_intResult
  // }.elsewhen(
  //   wbForwardValid && (memoryWritebackReg.io.writeback_rd === exeRs2)
  // ) {
  //   forwardRs2 := memoryWritebackReg.io.writeback_result
  // }

  // execute.io.regFile.readData1 := forwardRs1
  // execute.io.regFile.readData2 := forwardRs2

  // // ===== EXECUTE -> MEMORY REGISTER =====
  // executeMemoryReg.io.execute_opType := execute.io.opType
  // executeMemoryReg.io.execute_rd := execute.io.rd
  // executeMemoryReg.io.execute_regWrite := execute.io.regWrite
  // executeMemoryReg.io.execute_intResult := execute.io.intResult
  // executeMemoryReg.io.execute_memAddress := execute.io.memAddress
  // executeMemoryReg.io.execute_memWidth := execute.io.memWidth
  // executeMemoryReg.io.execute_memUnsigned := execute.io.memUnsigned
  // executeMemoryReg.io.execute_storeData := execute.io.storeData
  // executeMemoryReg.io.execute_pc := decodeExecuteReg.io.execute_uop.pc
  // executeMemoryReg.io.execute_isEcall := decodeExecuteReg.io.execute_uop.isEcall
  // executeMemoryReg.io.stall := memory_stall
  // executeMemoryReg.io.flush := false.B // Don't flush - let branch/jump instruction complete

  // // ===== MEMORY STAGE =====
  // memory.io.opType := executeMemoryReg.io.memory_opType
  // memory.io.rd := executeMemoryReg.io.memory_rd
  // memory.io.regWrite := executeMemoryReg.io.memory_regWrite
  // memory.io.intResult := executeMemoryReg.io.memory_intResult
  // memory.io.memAddress := executeMemoryReg.io.memory_memAddress
  // memory.io.memWidth := executeMemoryReg.io.memory_memWidth
  // memory.io.memUnsigned := executeMemoryReg.io.memory_memUnsigned
  // memory.io.storeData := executeMemoryReg.io.memory_storeData

  // // Connect data cache - Memory produces requests, CPU forwards to external
  // memory.mem <> io.datamem

  // // ===== MEMORY -> WRITEBACK REGISTER =====
  // memoryWritebackReg.io.memory_opType := memory.io.wbOpType
  // memoryWritebackReg.io.memory_rd := memory.io.wbRd
  // memoryWritebackReg.io.memory_regWrite := memory.io.wbRegWrite
  // memoryWritebackReg.io.memory_result := memory.io.wbResult
  // memoryWritebackReg.io.memory_pc := executeMemoryReg.io.memory_pc
  // memoryWritebackReg.io.memory_isEcall := executeMemoryReg.io.memory_isEcall
  // memoryWritebackReg.io.stall := memory_stall
  // memoryWritebackReg.io.flush := false.B // Don't flush - let branch/jump instruction complete

  // // ===== WRITEBACK STAGE =====
  // writeback.io.opType := memoryWritebackReg.io.writeback_opType
  // writeback.io.rd := memoryWritebackReg.io.writeback_rd
  // writeback.io.regWrite := memoryWritebackReg.io.writeback_regWrite
  // writeback.io.result := memoryWritebackReg.io.writeback_result

  // // Connect Writeback to RegFile write port
  // regFile.writeIo.writeEn := writeback.io.regFile.writeEn
  // regFile.writeIo.writeAddr := writeback.io.regFile.writeAddr
  // regFile.writeIo.writeData := writeback.io.regFile.writeData

  // val ecallWritePending = RegInit(0.U(2.W))
  // val ecallWriteIndex = RegInit(0.U(2.W))
  // val startEcallWrites =
  //   memoryWritebackReg.io.writeback_isEcall && (io.tohostAddr =/= 0.U)

  // when(startEcallWrites) {
  //   ecallWritePending := 2.U
  //   ecallWriteIndex := 0.U
  // }.elsewhen(ecallWritePending =/= 0.U) {
  //   ecallWritePending := ecallWritePending - 1.U
  //   ecallWriteIndex := ecallWriteIndex + 1.U
  // }

  // val extraWriteEn = ecallWritePending =/= 0.U
  // val extraWriteAddr = WireDefault(0.U(5.W))
  // val extraWriteData = WireDefault(0.U(config.xlen.W))
  // when(ecallWritePending =/= 0.U) {
  //   when(ecallWriteIndex === 0.U) {
  //     extraWriteAddr := 30.U
  //     extraWriteData := io.tohostAddr + "h3c".U
  //   }.otherwise {
  //     extraWriteAddr := 31.U
  //     extraWriteData := 8.U
  //   }
  // }
  // regFile.extraWriteEn := extraWriteEn
  // regFile.extraWriteAddr := extraWriteAddr
  // regFile.extraWriteData := extraWriteData

  // val wbPcWire = Wire(UInt(config.xlen.W))
  // wbPcWire := memoryWritebackReg.io.writeback_pc
  // BoringUtils.addSource(wbPcWire, "cpu_debug_writeback_pc")
}
