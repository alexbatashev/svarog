package svarog.micro

import chisel3._
import chisel3.util._

class HazardUnitIO extends Bundle {
  // Current registers in decode
  val decode_rs1 = Input(UInt(5.W))
  val decode_rs2 = Input(UInt(5.W))
  val decode_usesRs2 = Input(Bool())
  val decode_valid = Input(Bool())

  // Instructions in pipeline that might cause hazards
  // Execute stage
  val execute_rd = Input(UInt(5.W))
  val execute_regWrite = Input(Bool())
  val execute_valid = Input(Bool())

  // Memory stage
  val memory_rd = Input(UInt(5.W))
  val memory_regWrite = Input(Bool())
  val memory_valid = Input(Bool())

  // Outputs
  val stall = Output(Bool()) // Stall Fetch and Decode
  val bubble = Output(Bool()) // Insert bubble into Execute
}

class HazardUnit extends Module {
  val io = IO(new HazardUnitIO)

  // Check if current instruction reads a register that's being written
  // by an instruction in Execute or Memory stage

  // Hazard conditions:
  // 1. Execute stage is writing to a register (regWrite && valid)
  // 2. That register matches rs1 or rs2
  // 3. The register is not x0 (writes to x0 are ignored)

  val executeHazard_rs1 = io.execute_regWrite && io.execute_valid &&
    (io.execute_rd === io.decode_rs1) &&
    (io.decode_rs1 =/= 0.U)

  val executeHazard_rs2 = io.execute_regWrite && io.execute_valid &&
    (io.execute_rd === io.decode_rs2) &&
    (io.decode_rs2 =/= 0.U) &&
    io.decode_usesRs2 // Only check rs2 if instruction uses it

  val memoryHazard_rs1 = io.memory_regWrite && io.memory_valid &&
    (io.memory_rd === io.decode_rs1) &&
    (io.decode_rs1 =/= 0.U)

  val memoryHazard_rs2 = io.memory_regWrite && io.memory_valid &&
    (io.memory_rd === io.decode_rs2) &&
    (io.decode_rs2 =/= 0.U) &&
    io.decode_usesRs2 // Only check rs2 if instruction uses it

  // Detect any hazard
  // Check both Execute and Memory stages. We need to stall until the producing
  // instruction reaches Writeback, because our register file doesn't have
  // internal forwarding (write-then-read in same cycle).
  val hasHazard = (executeHazard_rs1 || executeHazard_rs2 ||
    memoryHazard_rs1 || memoryHazard_rs2) && io.decode_valid

  // Output signals
  io.stall := hasHazard // Stall Fetch and Decode stages
  io.bubble := hasHazard // Insert bubble (NOP) into Execute stage

  // Note: We don't check Writeback stage because of register file design
  // The register file is written in the first half of the cycle and read
  // in the second half, so there's no hazard with Writeback stage
}
