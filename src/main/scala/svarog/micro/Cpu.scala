package svarog.micro

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import svarog.bits.{CSRReadIO, CSRWriteIO}
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
import svarog.MicroCoreConfig
import svarog.config.Cluster
import svarog.csr.{
  CSRBusAdapter,
  CSRXbar,
  MachineInfoCSR,
  MachineCSR,
  InterruptCSR
}

class CpuIO(xlen: Int) extends Bundle {
  // Memory interfaces (TileLink adapters are in MicroTile)
  val instMem = new MemoryIO(xlen, xlen)
  val dataMem = new MemoryIO(xlen, xlen)
  // Debug and control
  val debug = Flipped(new HartDebugIO(xlen))
  val debugRegData = Valid(UInt(xlen.W))
  val halt = Output(Bool())
  val timerInterrupt = Input(Bool())
}

class Cpu(
    val hartId: Int,
    val config: Cluster,
    val startAddress: Long
)(implicit p: Parameters)
    extends LazyModule {

  private val xlen = config.isa.xlen

  // CSR diplomatic subsystem - core-local
  val csrAdapter = LazyModule(new CSRBusAdapter)
  val csrXbar = LazyModule(new CSRXbar)
  val machineInfoCSR = LazyModule(new MachineInfoCSR(hartId))
  val machineCSR = LazyModule(new MachineCSR(xlen))
  val interruptCSR = LazyModule(new InterruptCSR)

  // Connect CSR nodes
  csrXbar.node := csrAdapter.node
  machineInfoCSR.node := csrXbar.node
  machineCSR.node := csrXbar.node
  interruptCSR.node := csrXbar.node

  lazy val module = new CpuImp(this)
}

class CpuImp(outer: Cpu) extends LazyModuleImp(outer) {
  private val config = outer.config
  private val xlen = config.isa.xlen
  private val startAddress = outer.startAddress

  val io = IO(new CpuIO(xlen))

  val debug = Module(new HartDebugModule(xlen))
  val halt = RegInit(false.B)
  halt := debug.io.halt

  // Connect debug interface
  debug.io.hart <> io.debug
  io.debugRegData <> debug.io.regData
  io.halt := halt

  // Memories
  val regFile = Module(new RegFile(xlen))

  // Stages
  val fetch = Module(new Fetch(xlen, startAddress))
  val decode = Module(new SimpleDecoder(xlen))
  val execute = Module(new Execute(config.isa))
  val memory = Module(new Memory(xlen))
  val writeback = Module(new Writeback(xlen))

  val hazardUnit = Module(new HazardUnit)

  // Connect memory interfaces (adapters are in MicroTile)
  fetch.io.mem <> io.instMem
  memory.mem <> io.dataMem

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

  // CSR file connection - internal to Cpu via diplomatic CSR subsystem
  outer.csrAdapter.module.io.read <> execute.io.csrFile.read
  outer.csrAdapter.module.io.write <> writeback.io.csrFile

  // Connect interrupt signals to InterruptCSR
  outer.interruptCSR.module.io.timerInterrupt := io.timerInterrupt
  outer.interruptCSR.module.io.softwareInterrupt := false.B
  outer.interruptCSR.module.io.externalInterrupt := false.B

  // IF -> ID
  val fetchDecodeQueue = Module(
    new Queue(new InstWord(xlen), 1, pipe = true, hasFlush = true)
  )

  fetchDecodeQueue.io.enq <> fetch.io.inst_out
  decode.io.inst <> fetchDecodeQueue.io.deq

  // ID -> EX
  val decodeExecQueue = Module(
    new Queue(new MicroOp(xlen), 1, pipe = true, hasFlush = true)
  )
  decodeExecQueue.io.enq <> decode.io.decoded
  execute.io.uop <> decodeExecQueue.io.deq

  // EX -> MEM
  val execMemQueue = Module(
    new Queue(
      new ExecuteResult(xlen),
      1,
      pipe = true,
      hasFlush = true
    )
  )
  execMemQueue.io.enq <> execute.io.res
  memory.io.ex <> execMemQueue.io.deq

  // MEM -> WB
  val memWbQueue = Module(
    new Queue(new MemResult(xlen), 1, pipe = true, hasFlush = true)
  )
  memWbQueue.io.enq <> memory.io.res
  writeback.io.in <> memWbQueue.io.deq

  // Branch flush pipeline queues on branch mispredict (including the cycle after branch resolution
  // to cover the extra cycle of latency in the fetch redirect path).
  val branchFlushNow = execute.io.branch.valid
  val branchFlushHold = RegNext(branchFlushNow, init = false.B)
  val branchFlush = branchFlushNow || branchFlushHold

  // Exception handling - connect exception to MachineCSR
  val exceptionValid = execute.io.exception.valid
  outer.machineCSR.module.io.trapEnter.valid := exceptionValid
  outer.machineCSR.module.io.trapEnter.bits.epc := execute.io.exception.bits.epc
  outer.machineCSR.module.io.trapEnter.bits.cause := execute.io.exception.bits.cause

  // MRET handling
  outer.machineCSR.module.io.mretFired := execute.io.mretFired

  // Connect mepc to Execute for MRET target
  execute.io.mepc := outer.machineCSR.module.io.mepc

  // Backprop pipes for branch feedback
  val execFetchPipe = Module(new Pipe(new BranchFeedback(xlen)))
  execFetchPipe.io.enq <> execute.io.branch

  // Exception redirect to mtvec - pipe through to Fetch like branch
  val exceptionRedirectPipe = Module(new Pipe(new BranchFeedback(xlen)))
  exceptionRedirectPipe.io.enq.valid := exceptionValid
  exceptionRedirectPipe.io.enq.bits.targetPC := outer.machineCSR.module.io.mtvec

  // Combine branch and exception redirects for Fetch
  // Exception takes priority if both occur on same cycle (shouldn't happen normally)
  fetch.io.branch.valid := execFetchPipe.io.deq.valid || exceptionRedirectPipe.io.deq.valid
  fetch.io.branch.bits.targetPC := Mux(
    exceptionRedirectPipe.io.deq.valid,
    exceptionRedirectPipe.io.deq.bits.targetPC,
    execFetchPipe.io.deq.bits.targetPC
  )

  // Flush on exception or branch
  val exceptionFlush = exceptionValid
  val exceptionFlushHold = RegNext(exceptionFlush, init = false.B)
  val totalFlush = branchFlush || exceptionFlush || exceptionFlushHold

  // Flush all pipeline queues when debug sets PC or branch/exception
  val debugFlush = debug.io.setPCOut.valid
  fetchDecodeQueue.io.flush.get := debugFlush || totalFlush
  decodeExecQueue.io.flush.get := debugFlush || totalFlush
  execMemQueue.io.flush.get := debugFlush
  memWbQueue.io.flush.get := debugFlush

  // Debug connections
  debug.io.wbPC <> writeback.io.debugPC
  debug.io.memStore <> writeback.io.debugStore
  fetch.io.debugSetPC <> debug.io.setPCOut
  fetch.io.halt := halt

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
  execute.io.stall := hazardUnit.io.stall || halt || branchFlushHold || exceptionFlushHold
  writeback.io.halt := halt
}
