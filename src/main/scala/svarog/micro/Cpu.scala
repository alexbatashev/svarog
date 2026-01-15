package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.{
  ConstantCsrDevice,
  CSRBus,
  CSRReadMasterIO,
  CSRWriteMasterIO
}
import svarog.interrupt.{InterruptController, TrapController}
import svarog.bits.RegFile
import svarog.bits.RegFileReadIO
import svarog.bits.RegFileWriteIO
import svarog.debug.HartDebugIO
import svarog.debug.HartDebugModule
import svarog.decoder.InstWord
import svarog.decoder.MicroOp
import svarog.decoder.SimpleDecoder
import svarog.memory.CpuMemoryInterface
import svarog.memory.MemWishboneHost
import svarog.memory.MemoryIO
import svarog.memory.MemoryRequest
import svarog.MicroCoreConfig
import svarog.config.Cluster

class CpuIO(xlen: Int) extends Bundle {
  val instmem = new MemoryIO(xlen, xlen)
  val datamem = new MemoryIO(xlen, xlen)
  val debug = Flipped(new HartDebugIO(xlen))
  val debugRegData = Valid(UInt(xlen.W))
  val halt = Output(Bool()) // Expose halt status
}

class Cpu(
    hartId: Int,
    config: Cluster,
    startAddress: Long
) extends Module {
  // Public interface
  val io = IO(new CpuIO(config.isa.xlen))

  val debug = Module(new HartDebugModule(config.isa.xlen))
  val halt = RegInit(false.B)
  halt := debug.io.halt

  // Connect debug interface
  debug.io.hart <> io.debug
  io.debugRegData <> debug.io.regData
  io.halt := halt

  private val defaultCSRs = ConstantCsrDevice.getDefaultRegisters(hartId)

  // Memories
  val regFile = Module(new RegFile(config.isa.xlen))

  // Stages
  val fetch = Module(new Fetch(config.isa.xlen, startAddress))
  val decode = Module(new SimpleDecoder(config.isa.xlen))
  val execute = Module(new Execute(config.isa))
  val memory = Module(new Memory(config.isa.xlen))
  val writeback = Module(new Writeback(config.isa.xlen))

  val hazardUnit = Module(new HazardUnit)

  // CSR bus wiring
  private val csrReadMaster = Wire(new CSRReadMasterIO(config.isa.xlen))
  private val csrWriteMaster = Wire(new CSRWriteMasterIO(config.isa.xlen))

  execute.io.csrFile <> csrReadMaster
  writeback.io.csrWrite <> csrWriteMaster

  // Interrupt and trap controllers
  private val intController = Module(new InterruptController(config.isa.xlen))
  private val trapController = Module(new TrapController(config.isa.xlen, numTrapSources = 1))

  // Wire interrupt controller to trap controller
  trapController.io.interruptPending := intController.io.pending
  trapController.io.interruptCause := intController.io.cause

  // Default interrupt sources (no interrupts connected yet)
  intController.io.sources.mtip := false.B
  intController.io.sources.meip := false.B
  intController.io.msip := false.B

  // Default trap controller inputs (exceptions wired from pipeline later)
  trapController.io.exceptions(0).valid := false.B
  trapController.io.exceptions(0).bits.cause := 0.U
  trapController.io.exceptions(0).bits.tval := 0.U
  trapController.io.exceptions(0).bits.pc := 0.U
  trapController.io.exceptions(0).bits.isInterrupt := false.B
  trapController.io.currentPC := fetch.io.inst_out.bits.pc
  trapController.io.mret := false.B

  private val csrDevices = defaultCSRs.map(gen => Module(gen())) :+ trapController :+ intController
  CSRBus(csrReadMaster, csrWriteMaster, csrDevices)

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

class CpuWishbone(xlen: Int, maxReqWidth: Int)
    extends CpuMemoryInterface(xlen, maxReqWidth) {
  val inst = Module(new MemWishboneHost(xlen, maxReqWidth))
  val data = Module(new MemWishboneHost(xlen, maxReqWidth))

  val instPort = Wire(new MemoryIO(xlen, maxReqWidth))
  val dataPort = Wire(new MemoryIO(xlen, maxReqWidth))

  inst.mem <> instPort
  data.mem <> dataPort

  io.inst <> instPort
  io.data <> dataPort
}
