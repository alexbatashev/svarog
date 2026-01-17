package svarog.micro

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import svarog.bits.CSRFile
import svarog.bits.ControlRegister
import svarog.bits.RegFile
import svarog.bits.RegFileReadIO
import svarog.bits.RegFileWriteIO
import svarog.debug.HartDebugIO
import svarog.debug.HartDebugModule
import svarog.decoder.InstWord
import svarog.decoder.MicroOp
import svarog.decoder.SimpleDecoder
import svarog.memory.MemoryIO
import svarog.memory.MemoryRequest
import svarog.memory.MemoryIOTileLinkBundleAdapter
import svarog.MicroCoreConfig
import svarog.config.Cluster
import freechips.rocketchip.tilelink.{TLBundle, TLBundleParameters, TLEdgeOut}

class CpuIO(
    xlen: Int,
    instParams: TLBundleParameters,
    dataParams: TLBundleParameters
) extends Bundle {
  val inst = new TLBundle(instParams)
  val data = new TLBundle(dataParams)
  val debug = Flipped(new HartDebugIO(xlen))
  val debugRegData = Valid(UInt(xlen.W))
  val halt = Output(Bool())
  val timerInterrupt = Input(Bool())
}

class Cpu(
    hartId: Int,
    config: Cluster,
    startAddress: Long,
    instEdge: TLEdgeOut,
    dataEdge: TLEdgeOut
) extends Module {
  // Public interface
  val io = IO(new CpuIO(config.isa.xlen, instEdge.bundle, dataEdge.bundle))

  val debug = Module(new HartDebugModule(config.isa.xlen))
  val halt = RegInit(false.B)
  halt := debug.io.halt

  // Connect debug interface
  debug.io.hart <> io.debug
  io.debugRegData <> debug.io.regData
  io.halt := halt

  // Memories
  val regFile = Module(new RegFile(config.isa.xlen))
  val csrFile = Module(new CSRFile(ControlRegister.getDefaultRegisters()))

  // Stages
  val fetch = Module(new Fetch(config.isa.xlen, startAddress))
  val decode = Module(new SimpleDecoder(config.isa.xlen))
  val execute = Module(new Execute(config.isa))
  val memory = Module(new Memory(config.isa.xlen))
  val writeback = Module(new Writeback(config.isa.xlen))

  val hazardUnit = Module(new HazardUnit)

  // Fetch memory (TileLink)
  private val instAdapter =
    Module(new MemoryIOTileLinkBundleAdapter(instEdge, config.isa.xlen))
  fetch.io.mem <> instAdapter.mem
  io.inst <> instAdapter.tl

  // Data memory (TileLink)
  private val dataAdapter =
    Module(new MemoryIOTileLinkBundleAdapter(dataEdge, config.isa.xlen))
  memory.mem <> dataAdapter.mem
  io.data <> dataAdapter.tl

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
  // Simple writeback bypass so execute sees the most recent value without waiting
  val wbWriteEn = regFile.writeIo.writeEn
  val wbWriteAddr = regFile.writeIo.writeAddr
  val wbWriteData = regFile.writeIo.writeData

  def bypass(readAddr: UInt, readData: UInt): UInt = {
    Mux(
      wbWriteEn && (wbWriteAddr =/= 0.U) && (wbWriteAddr === readAddr),
      wbWriteData,
      readData
    )
  }

  execute.io.regFile.readData1 := bypass(
    execute.io.regFile.readAddr1,
    regFile.readIo.readData1
  )
  execute.io.regFile.readData2 := bypass(
    execute.io.regFile.readAddr2,
    regFile.readIo.readData2
  )
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

  // CSR file connection - read from Execute, write from Writeback
  execute.io.csrFile.read <> csrFile.io.read
  csrFile.io.write <> writeback.io.csrFile

  // IF -> ID
  // Increased depth from 1 to 4 to handle pipelining and stalls without losing instructions
  val fetchDecodeQueue = Module(
    new Queue(new InstWord(config.isa.xlen), 1, pipe = true, hasFlush = true)
  )

  fetchDecodeQueue.io.enq <> fetch.io.inst_out
  decode.io.inst <> fetchDecodeQueue.io.deq

  // ID -> EX
  val decodeExecQueue = Module(
    new Queue(new MicroOp(config.isa.xlen), 1, pipe = true, hasFlush = true)
  )
  decodeExecQueue.io.enq <> decode.io.decoded
  execute.io.uop <> decodeExecQueue.io.deq

  // EX -> MEM
  val execMemQueue = Module(
    new Queue(
      new ExecuteResult(config.isa.xlen),
      1,
      pipe = true,
      hasFlush = true
    )
  )
  execMemQueue.io.enq <> execute.io.res
  memory.io.ex <> execMemQueue.io.deq

  // MEM -> WB
  val memWbQueue = Module(
    new Queue(new MemResult(config.isa.xlen), 1, pipe = true, hasFlush = true)
  )
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
  val execFetchPipe = Module(new Pipe(new BranchFeedback(config.isa.xlen)))
  fetch.io.branch <> execFetchPipe.io.deq
  execFetchPipe.io.enq <> execute.io.branch

  // Flush pipeline queues on branch mispredict (including the cycle after branch resolution
  // to cover the extra cycle of latency in the fetch redirect path).
  val branchFlushNow = execute.io.branch.valid
  val branchFlushHold = RegNext(branchFlushNow, init = false.B)
  val branchFlush = branchFlushNow || branchFlushHold
  fetchDecodeQueue.flush := branchFlush
  decodeExecQueue.flush := branchFlush

  // Determine which source registers are actually used by the instruction currently in decode.
  // Hazard signals
  hazardUnit.io.decode <> decode.io.hazard
  hazardUnit.io.exec := execute.io.hazard
  hazardUnit.io.mem := memory.io.hazard
  hazardUnit.io.wb := writeback.io.hazard
  hazardUnit.io.execCsr := execute.io.csrHazard
  hazardUnit.io.memCsr := memory.io.csrHazard
  hazardUnit.io.wbCsr := writeback.io.csrHazard
  hazardUnit.io.watchpointHit := debug.io.watchpointTriggered
  execute.io.stall := hazardUnit.io.stall || halt || branchFlushHold
  writeback.io.halt := halt
}
