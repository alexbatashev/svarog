package svarog.micro

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.bits.RegFile
import svarog.soc.SvarogConfig
import svarog.memory.{MemoryRequest, MemoryIO}
import svarog.decoder.SimpleDecoder
import svarog.decoder.InstWord
import svarog.decoder.MicroOp
import svarog.decoder.SimpleDecodeHazardIO
import svarog.debug.HartDebugModule
import svarog.debug.HartDebugIO
import svarog.bits.RegFileReadIO
import svarog.bits.RegFileWriteIO

class CpuIO(xlen: Int) extends Bundle {
  val instmem = new MemoryIO(xlen, xlen)
  val datamem = new MemoryIO(xlen, xlen)
  val debug = Flipped(new HartDebugIO(xlen))
  val debugRegData = Valid(UInt(xlen.W))
  val halt = Output(Bool()) // Expose halt status
}

class Cpu(
    config: SvarogConfig,
    regfileProbeId: Option[String] = None,
    resetVector: BigInt = 0
) extends Module {
  // Public interface
  val io = IO(new CpuIO(config.xlen))

  val debug = Module(new HartDebugModule(config.xlen))
  val halt = RegInit(false.B)
  halt := debug.io.halt

  // Connect debug interface
  debug.io.hart <> io.debug
  io.debugRegData <> debug.io.regData
  io.halt := halt

  // Memories
  val regFile = Module(new RegFile(config.xlen))

  // Stages
  val fetch = Module(new Fetch(config.xlen, resetVector))
  val decode = Module(new SimpleDecoder(config.xlen))
  val execute = Module(new Execute(config.xlen))
  val memory = Module(new Memory(config.xlen))
  val writeback = Module(new Writeback(config.xlen))

  val hazardUnit = Module(new HazardUnit)

  // Fetch memory
  fetch.io.mem <> io.instmem

  // Data memory
  memory.mem <> io.datamem

  // Register file connection - multiplex between normal execution and debug
  // Read side
  regFile.readIo.readAddr1 := Mux(
    halt,
    debug.io.regRead.readAddr1,
    execute.io.regFile.readAddr1
  )
  regFile.readIo.readAddr2 := Mux(
    halt,
    debug.io.regRead.readAddr2,
    execute.io.regFile.readAddr2
  )
  execute.io.regFile.readData1 := regFile.readIo.readData1
  execute.io.regFile.readData2 := regFile.readIo.readData2
  debug.io.regRead.readData1 := regFile.readIo.readData1
  debug.io.regRead.readData2 := regFile.readIo.readData2

  // Write side
  // Debug writes take priority when debug module is actively writing
  // Otherwise, allow Writeback writes even when halted (to drain pipeline)
  regFile.writeIo.writeEn := Mux(
    debug.io.regWrite.writeEn,
    true.B,
    writeback.io.regFile.writeEn
  )
  regFile.writeIo.writeAddr := Mux(
    debug.io.regWrite.writeEn,
    debug.io.regWrite.writeAddr,
    writeback.io.regFile.writeAddr
  )
  regFile.writeIo.writeData := Mux(
    debug.io.regWrite.writeEn,
    debug.io.regWrite.writeData,
    writeback.io.regFile.writeData
  )

  regFile.extraWriteEn := false.B
  regFile.extraWriteAddr := 0.U
  regFile.extraWriteData := 0.U
  dontTouch(regFile.extraWriteEn)
  dontTouch(regFile.extraWriteAddr)
  dontTouch(regFile.extraWriteData)

  // IF -> ID
  // Increased depth from 1 to 4 to handle pipelining and stalls without losing instructions
  val fetchDecodeQueue = Module(new Queue(new InstWord(config.xlen), 1, hasFlush = true))

  fetchDecodeQueue.io.enq <> fetch.io.inst_out
  decode.io.inst <> fetchDecodeQueue.io.deq

  // ID -> EX
  val decodeExecQueue = Module(new Queue(new MicroOp(config.xlen), 1, hasFlush = true))
  decodeExecQueue.io.enq <> decode.io.decoded
  execute.io.uop <> decodeExecQueue.io.deq

  // EX -> MEM
  val execMemQueue = Module(new Queue(new ExecuteResult(config.xlen), 1, hasFlush = true))
  execMemQueue.io.enq <> execute.io.res
  memory.io.ex <> execMemQueue.io.deq

  // MEM -> WB
  val memWbQueue = Module(new Queue(new MemResult(config.xlen), 1, hasFlush = true))
  memWbQueue.io.enq <> memory.io.res
  writeback.io.in <> memWbQueue.io.deq

  // Flush all pipeline queues when debug sets PC
  val debugFlush = debug.io.setPCOut.valid
  fetchDecodeQueue.io.flush.get := debugFlush
  decodeExecQueue.io.flush.get := debugFlush
  execMemQueue.io.flush.get := debugFlush
  memWbQueue.io.flush.get := debugFlush

  // Debug connections
  debug.io.wbPC <> writeback.io.debugPC
  debug.io.memStore <> writeback.io.debugStore
  fetch.io.debugSetPC <> debug.io.setPCOut
  fetch.io.halt := halt

  // Backprop pipes
  val execFetchPipe = Module(new Pipe(new BranchFeedback(config.xlen)))
  fetch.io.branch <> execFetchPipe.io.deq
  execFetchPipe.io.enq <> execute.io.branch

  // Flush pipeline queues on branch mispredict
  val branchMispredict = execute.io.branch.valid
  fetchDecodeQueue.flush := branchMispredict
  decodeExecQueue.flush := branchMispredict

  // Hazard signals
  // IMPORTANT: Don't condition on queue ready - we need to detect hazards even when queue is full
  val hazardDecodeInfo = Wire(new SimpleDecodeHazardIO)
  hazardDecodeInfo.rs1 := 0.U
  hazardDecodeInfo.rs2 := 0.U
  when(decodeExecQueue.io.deq.valid) {
    hazardDecodeInfo.rs1 := decodeExecQueue.io.deq.bits.rs1
    hazardDecodeInfo.rs2 := Mux(
      decodeExecQueue.io.deq.bits.hasImm,
      0.U,
      decodeExecQueue.io.deq.bits.rs2
    )
  }

  hazardUnit.io.decode.valid := decodeExecQueue.io.deq.valid
  hazardUnit.io.decode.bits := hazardDecodeInfo
  hazardUnit.io.exec := execute.io.hazard
  hazardUnit.io.mem := memory.io.hazard
  hazardUnit.io.wb := writeback.io.hazard
  hazardUnit.io.watchpointHit := debug.io.watchpointTriggered
  execute.io.stall := hazardUnit.io.stall || halt
  writeback.io.halt := halt
}
