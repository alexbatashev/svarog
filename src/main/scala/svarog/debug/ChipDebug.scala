package svarog.debug

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.{IdRange, TransferSizes}
import freechips.rocketchip.tilelink.{
  TLClientNode,
  TLMasterParameters,
  TLMasterPortParameters
}
import svarog.memory.MemWidth
import svarog.bits.MemoryUtils

class ChipHartDebugIO(xlen: Int) extends Bundle {
  val id = Valid(UInt(8.W))
  val bits = new HartDebugIO(xlen)
}

class ChipMemoryDebugIO(xlen: Int) extends Bundle {
  val addr = UInt(xlen.W)
  val reqWidth = MemWidth()
  val instr = Bool()

  val write = Bool()
  val data = UInt(xlen.W)
}

class ChipDebugSimulatorIO(numHarts: Int, xlen: Int) extends Bundle {
  val hart_in = Input(new ChipHartDebugIO(xlen))
  val mem_in = Flipped(Decoupled(new ChipMemoryDebugIO(xlen)))
  val mem_res = Decoupled(UInt(xlen.W))
  val reg_res = Decoupled(UInt(xlen.W))
  val halted = Output(Bool())
}

class TLChipDebugModule(
    xlen: Int,
    numHarts: Int,
    instSourceId: IdRange,
    dataSourceId: IdRange
)(implicit p: Parameters)
    extends LazyModule {

  private val beatBytes = xlen / 8

  private def clientParams(name: String, id: IdRange) =
    TLMasterParameters.v1(
      name = name,
      sourceId = id,
      supportsProbe = TransferSizes(1, beatBytes),
      supportsGet = TransferSizes(1, beatBytes),
      supportsPutFull = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes)
    )

  val instNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(Seq(clientParams("debug_inst", instSourceId))))
  )
  val dataNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(Seq(clientParams("debug_data", dataSourceId))))
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val debug = IO(new ChipDebugSimulatorIO(numHarts, xlen))
    val harts = IO(Vec(numHarts, new HartDebugIO(xlen)))
    val cpuRegData = IO(Input(Valid(UInt(xlen.W))))
    val cpuHalted = IO(Input(Vec(numHarts, Bool())))

    private val (instOut, instEdge) = instNode.out(0)
    private val (dataOut, dataEdge) = dataNode.out(0)

    // Route hart commands to the appropriate hart
    for (i <- 0 until numHarts) {
      val hartSelected = debug.hart_in.id.valid && debug.hart_in.id.bits === i.U

      harts(i).halt.valid := Mux(hartSelected, debug.hart_in.bits.halt.valid, false.B)
      harts(i).halt.bits := Mux(hartSelected, debug.hart_in.bits.halt.bits, false.B)

      harts(i).breakpoint.valid := Mux(
        hartSelected,
        debug.hart_in.bits.breakpoint.valid,
        false.B
      )
      harts(i).breakpoint.bits.pc := Mux(
        hartSelected,
        debug.hart_in.bits.breakpoint.bits.pc,
        0.U
      )

      harts(i).watchpoint.valid := Mux(
        hartSelected,
        debug.hart_in.bits.watchpoint.valid,
        false.B
      )
      harts(i).watchpoint.bits.addr := Mux(
        hartSelected,
        debug.hart_in.bits.watchpoint.bits.addr,
        0.U
      )

      harts(i).register.valid := Mux(
        hartSelected,
        debug.hart_in.bits.register.valid,
        false.B
      )
      harts(i).register.bits.reg := Mux(
        hartSelected,
        debug.hart_in.bits.register.bits.reg,
        0.U
      )
      harts(i).register.bits.write := Mux(
        hartSelected,
        debug.hart_in.bits.register.bits.write,
        false.B
      )
      harts(i).register.bits.data := Mux(
        hartSelected,
        debug.hart_in.bits.register.bits.data,
        0.U
      )

      harts(i).setPC.valid := Mux(
        hartSelected,
        debug.hart_in.bits.setPC.valid,
        false.B
      )
      harts(i).setPC.bits.pc := Mux(
        hartSelected,
        debug.hart_in.bits.setPC.bits.pc,
        0.U
      )
    }

    // Pass through halt status
    debug.halted := cpuHalted(0)

    // Connect register results from CPU
    debug.reg_res.valid := cpuRegData.valid
    debug.reg_res.bits := cpuRegData.bits

    // Memory interface state machine
    val wordSize = xlen / 8

    object State extends ChiselEnum {
      val sIdle, sAWait, sDWait = Value
    }

    val state = RegInit(State.sIdle)
    val memIsInstr = RegInit(false.B)
    val memIsWrite = RegInit(false.B)
    val savedAddr = RegInit(0.U(xlen.W))
    val savedData = RegInit(0.U(xlen.W))
    val savedMask = RegInit(0.U(wordSize.W))
    val savedSize = RegInit(0.U(3.W))
    val memWordOffset = RegInit(0.U(log2Ceil(wordSize).W))

    // Accept new memory requests when idle
    debug.mem_in.ready := state === State.sIdle

    // Compute word-aligned address and byte offset
    val byteAddr = debug.mem_in.bits.addr
    val (wordAlignedAddr, wordOffset) =
      MemoryUtils.alignAddress(byteAddr, wordSize)

    // Generate shifted mask
    val shiftedMask =
      MemoryUtils.generateShiftedMask(debug.mem_in.bits.reqWidth, wordOffset, xlen)

    // Convert scalar data to shifted write data
    val writeDataBytes = Wire(Vec(wordSize, UInt(8.W)))
    for (i <- 0 until wordSize) {
      writeDataBytes(i) := debug.mem_in.bits.data((i + 1) * 8 - 1, i * 8)
    }
    val shiftedWriteData =
      MemoryUtils.shiftWriteData(writeDataBytes, wordOffset, wordSize)

    // Compute size from mask
    def maskToSize(mask: UInt): UInt = {
      val count = PopCount(mask)
      MuxLookup(count, 0.U(3.W))(
        Seq(
          1.U -> 0.U,
          2.U -> 1.U,
          4.U -> 2.U,
          8.U -> 3.U
        )
      )
    }

    // State machine
    when(state === State.sIdle && debug.mem_in.valid) {
      memIsInstr := debug.mem_in.bits.instr
      memIsWrite := debug.mem_in.bits.write
      savedAddr := byteAddr
      savedData := shiftedWriteData.asUInt
      savedMask := shiftedMask.asUInt
      savedSize := maskToSize(shiftedMask.asUInt)
      memWordOffset := wordOffset
      state := State.sAWait
    }

    // TileLink A channel - send request
    val instAValid = state === State.sAWait && memIsInstr
    val dataAValid = state === State.sAWait && !memIsInstr

    val instSrcId = instSourceId.start.U
    val dataSrcId = dataSourceId.start.U
    val (_, instGetA) = instEdge.Get(instSrcId, savedAddr, savedSize)
    val (_, instPutA) = instEdge.Put(instSrcId, savedAddr, savedSize, savedData, savedMask)
    val (_, dataGetA) = dataEdge.Get(dataSrcId, savedAddr, savedSize)
    val (_, dataPutA) = dataEdge.Put(dataSrcId, savedAddr, savedSize, savedData, savedMask)

    instOut.a.valid := instAValid
    instOut.a.bits := Mux(memIsWrite, instPutA, instGetA)

    dataOut.a.valid := dataAValid
    dataOut.a.bits := Mux(memIsWrite, dataPutA, dataGetA)

    when(state === State.sAWait) {
      when((memIsInstr && instOut.a.ready) || (!memIsInstr && dataOut.a.ready)) {
        state := State.sDWait
      }
    }

    // TileLink D channel - receive response
    instOut.d.ready := state === State.sDWait && memIsInstr && debug.mem_res.ready
    dataOut.d.ready := state === State.sDWait && !memIsInstr && debug.mem_res.ready

    val dValid =
      (memIsInstr && instOut.d.valid) || (!memIsInstr && dataOut.d.valid)
    val dData = Mux(memIsInstr, instOut.d.bits.data, dataOut.d.bits.data)

    // Unshift read data back based on byte offset
    val rawRespBytes = VecInit(
      Seq.tabulate(wordSize)(i => dData((i + 1) * 8 - 1, i * 8))
    )
    val shiftedRespBytes =
      MemoryUtils.unshiftReadData(rawRespBytes, memWordOffset, wordSize)
    val respData = shiftedRespBytes.asUInt

    debug.mem_res.valid := state === State.sDWait && dValid
    debug.mem_res.bits := respData

    when(state === State.sDWait && debug.mem_res.valid && debug.mem_res.ready) {
      state := State.sIdle
    }

    // Unused TileLink signals
    instOut.b.ready := true.B
    instOut.c.valid := false.B
    instOut.c.bits := DontCare
    instOut.e.valid := false.B
    instOut.e.bits := DontCare

    dataOut.b.ready := true.B
    dataOut.c.valid := false.B
    dataOut.c.bits := DontCare
    dataOut.e.valid := false.B
    dataOut.e.bits := DontCare
  }
}
