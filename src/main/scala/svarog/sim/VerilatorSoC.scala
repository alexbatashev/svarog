package svarog.sim

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.experimental.BoringUtils
import svarog.LoadableInstructionRom
import svarog.bits.{PeriphMux, UART}
import svarog.memory.BlockRamMemory
import svarog.micro.{Cpu, CpuDebugTap}

/**
 * Verilator-specific SoC with loadable instruction ROM.
 * This allows loading different programs without regenerating Verilog.
 */
class VerilatorSoC(
  xlen: Int = 32,
  romSizeBytes: Int = 16384,
  ramSizeBytes: Int = 16384,
  clockHz: Int = 50_000_000,
  baud: Int = 115200,
  regfileProbeId: Option[String] = None
) extends Module {
  private val firmwareBase = BigInt("80000000", 16)

  val io = IO(new Bundle {
    val uart = new Bundle {
      val tx = Output(Bool())
      val rx = Input(Bool())
    }
    val debug_regWrite = Output(Bool())
    val debug_writeAddr = Output(UInt(5.W))
    val debug_writeData = Output(UInt(xlen.W))
    val debug_pc = Output(UInt(xlen.W))
    val debug_branchValid = Output(Bool())
    val debug_branchRs1 = Output(UInt(xlen.W))
    val debug_branchRs2 = Output(UInt(xlen.W))
    val debug_branchTaken = Output(Bool())
    val debug_branchPc = Output(UInt(xlen.W))
    val watch_decode_hit = Output(Bool())
    val watch_decode_rd = Output(UInt(5.W))
    val watch_decode_regWrite = Output(Bool())
    val watch_decode_opType = Output(UInt(xlen.W))
    val watch_decode_imm = Output(UInt(xlen.W))
    val watch_decode_valid = Output(Bool())
    val watch_decode_reqValid = Output(Bool())
    val watch_decode_respValid = Output(Bool())
    val watch_frontend_stall = Output(Bool())
    val watch_execute_hit = Output(Bool())
    val watch_execute_rd = Output(UInt(5.W))
    val watch_execute_regWrite = Output(Bool())
    val watch_execute_opType = Output(UInt(xlen.W))
    val watch_execute_intResult = Output(UInt(xlen.W))
    val watch_execute_valid = Output(Bool())
    val watch_memory_hit = Output(Bool())
    val watch_memory_rd = Output(UInt(5.W))
    val watch_memory_regWrite = Output(Bool())
    val watch_memory_intResult = Output(UInt(xlen.W))
    val watch_writeback_hit = Output(Bool())
    val watch_writeback_rd = Output(UInt(5.W))
    val watch_writeback_regWrite = Output(Bool())
    val watch_writeback_result = Output(UInt(xlen.W))

    // ROM write port for testbench program loading
    val rom_write_en = Input(Bool())
    val rom_write_addr = Input(UInt(log2Ceil(romSizeBytes / 4).W))
    val rom_write_data = Input(UInt(32.W))
    val rom_write_mask = Input(UInt((xlen / 8).W))

    // Hold pipeline while testbench loads program
    val boot_hold = Input(Bool())

    // RAM preload port
    val ram_write_en = Input(Bool())
    val ram_write_addr = Input(UInt(log2Ceil(ramSizeBytes / 4).W))
    val ram_write_data = Input(UInt(xlen.W))
    val ram_write_mask = Input(UInt((xlen / 8).W))
  })

  private val debugTapId = "verilator"
  private val cpu = Module(new Cpu(
    xlen,
    regfileProbeId = regfileProbeId,
    debugTapId = Some(debugTapId),
    resetVector = firmwareBase
  ))
  private val instrRom = Module(new LoadableInstructionRom(xlen, romSizeBytes, baseAddr = firmwareBase))
  private val dmem = Module(new BlockRamMemory(xlen, ramSizeBytes, None, baseAddr = firmwareBase))
  private val periph = Module(new PeriphMux(xlen))
  private val uart = Module(new UART(xlen, clockHz, baud))

  cpu.io.icache <> instrRom.io.cpu
  instrRom.io.write_en := io.rom_write_en
  instrRom.io.write_addr := io.rom_write_addr
  instrRom.io.write_data := io.rom_write_data
  instrRom.io.write_mask := io.rom_write_mask
  cpu.io.bootHold := io.boot_hold

  periph.io.cpu <> cpu.io.dcache
  periph.io.ram <> dmem.io
  periph.io.uart <> uart.io.bus
  dmem.preload.en := io.ram_write_en
  dmem.preload.addr := io.ram_write_addr
  dmem.preload.data := io.ram_write_data
  dmem.preload.mask := io.ram_write_mask

  uart.io.rx := io.uart.rx
  io.uart.tx := uart.io.tx

  private val debugRegWrite = Wire(Bool())
  private val debugWriteAddr = Wire(UInt(5.W))
  private val debugWriteData = Wire(UInt(xlen.W))
  private val debugPc = Wire(UInt(xlen.W))
  private val debugBranchValid = Wire(Bool())
  private val debugBranchRs1 = Wire(UInt(xlen.W))
  private val debugBranchRs2 = Wire(UInt(xlen.W))
  private val debugBranchTaken = Wire(Bool())
  private val debugBranchPc = Wire(UInt(xlen.W))
  private val watchDecodeHit = Wire(Bool())
  private val watchDecodeRd = Wire(UInt(5.W))
  private val watchDecodeRegWrite = Wire(Bool())
  private val watchDecodeOpType = Wire(UInt(xlen.W))
  private val watchDecodeImm = Wire(UInt(xlen.W))
  private val watchDecodeValid = Wire(Bool())
  private val watchExecuteHit = Wire(Bool())
  private val watchExecuteRd = Wire(UInt(5.W))
  private val watchExecuteRegWrite = Wire(Bool())
  private val watchExecuteOpType = Wire(UInt(xlen.W))
  private val watchExecuteIntResult = Wire(UInt(xlen.W))
  private val watchExecuteValid = Wire(Bool())
  private val watchMemoryHit = Wire(Bool())
  private val watchMemoryRd = Wire(UInt(5.W))
  private val watchMemoryRegWrite = Wire(Bool())
  private val watchMemoryIntResult = Wire(UInt(xlen.W))
  private val watchWritebackHit = Wire(Bool())
  private val watchWritebackRd = Wire(UInt(5.W))
  private val watchWritebackRegWrite = Wire(Bool())
  private val watchWritebackResult = Wire(UInt(xlen.W))
  private val watchDecodeReqValid = Wire(Bool())
  private val watchDecodeRespValid = Wire(Bool())
  private val watchFrontEndStall = Wire(Bool())
  BoringUtils.addSink(debugRegWrite, CpuDebugTap.regWrite(debugTapId))
  BoringUtils.addSink(debugWriteAddr, CpuDebugTap.writeAddr(debugTapId))
  BoringUtils.addSink(debugWriteData, CpuDebugTap.writeData(debugTapId))
  BoringUtils.addSink(debugPc, CpuDebugTap.pc(debugTapId))
  BoringUtils.addSink(debugBranchValid, CpuDebugTap.branchValid(debugTapId))
  BoringUtils.addSink(debugBranchRs1, CpuDebugTap.branchRs1(debugTapId))
  BoringUtils.addSink(debugBranchRs2, CpuDebugTap.branchRs2(debugTapId))
  BoringUtils.addSink(debugBranchTaken, CpuDebugTap.branchTaken(debugTapId))
  BoringUtils.addSink(debugBranchPc, CpuDebugTap.branchPc(debugTapId))
  BoringUtils.addSink(watchDecodeHit, CpuDebugTap.watchDecodeHit(debugTapId))
  BoringUtils.addSink(watchDecodeRd, CpuDebugTap.watchDecodeRd(debugTapId))
  BoringUtils.addSink(watchDecodeRegWrite, CpuDebugTap.watchDecodeRegWrite(debugTapId))
  BoringUtils.addSink(watchDecodeOpType, CpuDebugTap.watchDecodeOpType(debugTapId))
  BoringUtils.addSink(watchDecodeImm, CpuDebugTap.watchDecodeImm(debugTapId))
  BoringUtils.addSink(watchDecodeValid, CpuDebugTap.watchDecodeValid(debugTapId))
  BoringUtils.addSink(watchDecodeReqValid, CpuDebugTap.watchDecodeReqValid(debugTapId))
  BoringUtils.addSink(watchDecodeRespValid, CpuDebugTap.watchDecodeRespValid(debugTapId))
  BoringUtils.addSink(watchFrontEndStall, CpuDebugTap.watchFrontEndStall(debugTapId))
  BoringUtils.addSink(watchExecuteHit, CpuDebugTap.watchExecuteHit(debugTapId))
  BoringUtils.addSink(watchExecuteRd, CpuDebugTap.watchExecuteRd(debugTapId))
  BoringUtils.addSink(watchExecuteRegWrite, CpuDebugTap.watchExecuteRegWrite(debugTapId))
  BoringUtils.addSink(watchExecuteOpType, CpuDebugTap.watchExecuteOpType(debugTapId))
  BoringUtils.addSink(watchExecuteIntResult, CpuDebugTap.watchExecuteIntResult(debugTapId))
  BoringUtils.addSink(watchExecuteValid, CpuDebugTap.watchExecuteValid(debugTapId))
  BoringUtils.addSink(watchMemoryHit, CpuDebugTap.watchMemoryHit(debugTapId))
  BoringUtils.addSink(watchMemoryRd, CpuDebugTap.watchMemoryRd(debugTapId))
  BoringUtils.addSink(watchMemoryRegWrite, CpuDebugTap.watchMemoryRegWrite(debugTapId))
  BoringUtils.addSink(watchMemoryIntResult, CpuDebugTap.watchMemoryIntResult(debugTapId))
  BoringUtils.addSink(watchWritebackHit, CpuDebugTap.watchWritebackHit(debugTapId))
  BoringUtils.addSink(watchWritebackRd, CpuDebugTap.watchWritebackRd(debugTapId))
  BoringUtils.addSink(watchWritebackRegWrite, CpuDebugTap.watchWritebackRegWrite(debugTapId))
  BoringUtils.addSink(watchWritebackResult, CpuDebugTap.watchWritebackResult(debugTapId))
  io.debug_regWrite := debugRegWrite
  io.debug_writeAddr := debugWriteAddr
  io.debug_writeData := debugWriteData
  io.debug_pc := debugPc
  io.debug_branchValid := debugBranchValid
  io.debug_branchRs1 := debugBranchRs1
  io.debug_branchRs2 := debugBranchRs2
  io.debug_branchTaken := debugBranchTaken
  io.debug_branchPc := debugBranchPc
  io.watch_decode_hit := watchDecodeHit
  io.watch_decode_rd := watchDecodeRd
  io.watch_decode_regWrite := watchDecodeRegWrite
  io.watch_decode_opType := watchDecodeOpType
  io.watch_decode_imm := watchDecodeImm
  io.watch_decode_valid := watchDecodeValid
  io.watch_decode_reqValid := watchDecodeReqValid
  io.watch_decode_respValid := watchDecodeRespValid
  io.watch_frontend_stall := watchFrontEndStall
  io.watch_execute_hit := watchExecuteHit
  io.watch_execute_rd := watchExecuteRd
  io.watch_execute_regWrite := watchExecuteRegWrite
  io.watch_execute_opType := watchExecuteOpType
  io.watch_execute_intResult := watchExecuteIntResult
  io.watch_execute_valid := watchExecuteValid
  io.watch_memory_hit := watchMemoryHit
  io.watch_memory_rd := watchMemoryRd
  io.watch_memory_regWrite := watchMemoryRegWrite
  io.watch_memory_intResult := watchMemoryIntResult
  io.watch_writeback_hit := watchWritebackHit
  io.watch_writeback_rd := watchWritebackRd
  io.watch_writeback_regWrite := watchWritebackRegWrite
  io.watch_writeback_result := watchWritebackResult
}
