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
import svarog.debug.HartDebugModule
import svarog.debug.HartDebugIO
import svarog.bits.RegFileReadIO
import svarog.bits.RegFileWriteIO

class CpuIO(xlen: Int) extends Bundle {
  val instmem = new MemoryIO(xlen, xlen)
  val datamem = new MemoryIO(xlen, xlen)
  val debug = Flipped(new HartDebugIO(xlen))
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

  // Memories
  val regFile = Module(new RegFile(config.xlen, regfileProbeId))

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
  regFile.readIo.readAddr1 := Mux(halt, debug.io.regRead.readAddr1, execute.io.regFile.readAddr1)
  regFile.readIo.readAddr2 := Mux(halt, debug.io.regRead.readAddr2, execute.io.regFile.readAddr2)
  execute.io.regFile.readData1 := regFile.readIo.readData1
  execute.io.regFile.readData2 := regFile.readIo.readData2
  debug.io.regRead.readData1 := regFile.readIo.readData1
  debug.io.regRead.readData2 := regFile.readIo.readData2

  // Write side
  regFile.writeIo.writeEn := Mux(halt, debug.io.regWrite.writeEn, writeback.io.regFile.writeEn)
  regFile.writeIo.writeAddr := Mux(halt, debug.io.regWrite.writeAddr, writeback.io.regFile.writeAddr)
  regFile.writeIo.writeData := Mux(halt, debug.io.regWrite.writeData, writeback.io.regFile.writeData)

  regFile.extraWriteEn := false.B
  regFile.extraWriteAddr := 0.U
  regFile.extraWriteData := 0.U
  dontTouch(regFile.extraWriteEn)
  dontTouch(regFile.extraWriteAddr)
  dontTouch(regFile.extraWriteData)

  // IF -> ID
  val fetchDecodeQueue = Module(new Queue(new InstWord(config.xlen), 1))

  fetchDecodeQueue.io.enq <> fetch.io.inst_out
  decode.io.inst <> fetchDecodeQueue.io.deq

  // ID -> EX
  val decodeExecQueue = Module(new Queue(new MicroOp(config.xlen), 1))
  decodeExecQueue.io.enq <> decode.io.decoded
  execute.io.uop <> decodeExecQueue.io.deq

  // EX -> MEM
  val execMemQueue = Module(new Queue(new ExecuteResult(config.xlen), 1))
  execMemQueue.io.enq <> execute.io.res
  memory.io.ex <> execMemQueue.io.deq

  // MEM -> WB
  val memWbQueue = Module(new Queue(new MemResult(config.xlen), 1))
  memWbQueue.io.enq <> memory.io.res
  writeback.io.in <> memWbQueue.io.deq

  // Debug connections
  debug.io.wbPC <> writeback.io.debugPC

  // Backprop pipes
  val execFetchPipe = Module(new Pipe(new BranchFeedback(config.xlen)))
  fetch.io.branch <> execFetchPipe.io.deq
  execFetchPipe.io.enq <> execute.io.branch

  // Hazard signals
  hazardUnit.io.decode := decode.io.hazard
  hazardUnit.io.exec := execute.io.hazard
  hazardUnit.io.mem := memory.io.hazard
  hazardUnit.io.wb := writeback.io.hazard
  execute.io.stall := hazardUnit.io.stall || halt
  writeback.io.halt := halt
}
