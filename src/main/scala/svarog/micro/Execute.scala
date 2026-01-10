package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.RegFileReadIO
import svarog.bits.ALU
import svarog.bits.{MulOp, SimpleMultiplier}
import svarog.bits.{DivOp, SimpleDivider}
import svarog.decoder.BranchOp
import svarog.decoder.{MicroOp, OpType}
import svarog.memory.MemWidth
import svarog.bits.{CSREx, CSRReadMasterIO}
import svarog.config.ISA

class ExecuteResult(xlen: Int) extends Bundle {
  val opType = Output(OpType())

  val rd = Output(UInt(5.W))
  val gprWrite = Output(Bool())
  val gprResult = Output(UInt(xlen.W))

  val csrAddr = Output(UInt(12.W))
  val csrWrite = Output(Bool())
  val csrResult = Output(UInt(xlen.W))

  val memAddress = Output(UInt(xlen.W))
  val memWidth = Output(MemWidth())
  val memUnsigned = Output(Bool())

  val storeData = Output(UInt(xlen.W))

  val pc = Output(UInt(xlen.W))
  val instruction = Output(UInt(32.W))  // Raw instruction bits for trap handling
}

class BranchFeedback(xlen: Int) extends Bundle {
  val targetPC = Output(UInt(xlen.W))
}

class Execute(isa: ISA) extends Module {
  private val xlen = isa.xlen

  val io = IO(new Bundle {
    val uop = Flipped(Decoupled(new MicroOp(xlen)))
    val res = Decoupled(new ExecuteResult(xlen))
    val branch = Valid(new BranchFeedback(xlen))

    val regFile = Flipped(new RegFileReadIO(xlen))
    val csrFile = Flipped(new CSRReadMasterIO(xlen))

    // Write register for hazard control
    val hazard = Valid(UInt(5.W))
    val csrHazard = Valid(new HazardUnitCSRIO)
    val stall = Input(Bool())
  })

  // If the branch is mispredicted on current cycle, whatever instruction
  // comes on next cycle must be dropped
  val needFlush = RegInit(false.B)
  needFlush := false.B // reset at each cycle

  val alu = Module(new ALU(xlen))
  val mul = if (isa.zmmul) Some(Module(new SimpleMultiplier(xlen))) else None
  val div = if (isa.mult) Some(Module(new SimpleDivider(xlen))) else None
  val csr = Module(new CSREx(xlen))

  // Track multi-cycle operations
  val executingMultiCycle = RegInit(false.B)
  val bufferedUop = Reg(new MicroOp(xlen))

  // Determine if current instruction is multi-cycle
  val isMultiCycle = io.uop.valid && (
    io.uop.bits.opType === OpType.MUL ||
      io.uop.bits.opType === OpType.DIV
  )

  // Check if multi-cycle operation completes
  val multiCycleComplete = WireDefault(false.B)
  mul.foreach { m => when(m.io.result.valid) { multiCycleComplete := true.B } }
  div.foreach { d => when(d.io.result.valid) { multiCycleComplete := true.B } }

  // Buffer the MicroOp when starting a multi-cycle operation
  when(io.uop.fire && isMultiCycle) {
    executingMultiCycle := true.B
    bufferedUop := io.uop.bits
  }

  // Clear the flag when operation completes
  when(multiCycleComplete && io.res.fire) {
    executingMultiCycle := false.B
  }

  // Use buffered MicroOp during multi-cycle execution
  val activeUop =
    Mux(executingMultiCycle || multiCycleComplete, bufferedUop, io.uop.bits)

  // This execution unit is not fully pipelined. New instructions can only be
  // accepted when all of the FUs are ready and no multi-cycle op is executing.
  val canDequeue = io.res.ready && !io.stall && !executingMultiCycle
  io.uop.ready := canDequeue

  // Output is valid for single-cycle ops or when multi-cycle completes
  io.res.valid := (io.uop.valid && canDequeue && !isMultiCycle) || (multiCycleComplete && !needFlush)

  // Hazard signals should be valid during entire multi-cycle operation
  val hazardActive =
    (io.uop.valid && !isMultiCycle) || executingMultiCycle || multiCycleComplete
  io.hazard.valid := hazardActive && activeUop.regWrite && !(activeUop.rd === 0.U)
  io.hazard.bits := activeUop.rd

  io.csrHazard.valid := hazardActive && (
    activeUop.opType === OpType.CSRRW ||
      activeUop.opType === OpType.CSRRS ||
      activeUop.opType === OpType.CSRRC ||
      activeUop.opType === OpType.INVALID  // Trap instruction
  )
  io.csrHazard.bits.addr := activeUop.csrAddr
  io.csrHazard.bits.isWrite := csr.io.csrWrite.valid || (activeUop.opType === OpType.INVALID)
  io.csrHazard.bits.isTrap := (activeUop.opType === OpType.INVALID)

  io.res.bits.opType := activeUop.opType
  io.res.bits.pc := activeUop.pc
  io.res.bits.instruction := activeUop.instruction

  // ALU result
  io.res.bits.gprResult := 0.U

  // Branch/jump target address
  io.branch.bits.targetPC := 0.U
  io.branch.valid := false.B

  // Load/store address
  io.res.bits.memAddress := 0.U
  io.res.bits.memWidth := activeUop.memWidth
  io.res.bits.memUnsigned := activeUop.memUnsigned

  // Store data
  io.res.bits.storeData := 0.U

  io.res.bits.rd := activeUop.rd
  io.res.bits.gprWrite := activeUop.regWrite

  // CSR defaults
  io.res.bits.csrAddr := activeUop.csrAddr
  io.res.bits.csrWrite := false.B
  io.res.bits.csrResult := 0.U

  io.regFile.readAddr1 := activeUop.rs1
  io.regFile.readAddr2 := activeUop.rs2

  alu.io.op := activeUop.aluOp
  alu.io.input1 := io.regFile.readData1
  alu.io.input2 := Mux(
    activeUop.hasImm,
    activeUop.imm,
    io.regFile.readData2
  )

  // Multiplier wiring
  mul.foreach { mul =>
    mul.io.inp.bits.op := activeUop.mulOp
    mul.io.inp.bits.multiplicant := io.regFile.readData1
    mul.io.inp.bits.multiplier := io.regFile.readData2
    mul.io.inp.valid := io.uop.valid && (io.uop.bits.opType === OpType.MUL)
  }

  // Divider wiring
  div.foreach { div =>
    div.io.inp.bits.op := activeUop.divOp
    div.io.inp.bits.dividend := io.regFile.readData1
    div.io.inp.bits.divisor := io.regFile.readData2
    div.io.inp.valid := io.uop.valid && (io.uop.bits.opType === OpType.DIV)
  }

  // CSR wiring
  csr.io.uop.valid := io.uop.valid
  csr.io.uop.bits := activeUop
  csr.io.rs1Value := io.regFile.readData1

  io.csrFile.valid := csr.io.csr.valid
  io.csrFile.addr := csr.io.csr.addr
  csr.io.csr.data := io.csrFile.data

  when(
    (io.uop.valid || executingMultiCycle || multiCycleComplete) && !needFlush
  ) {
    switch(activeUop.opType) {
      is(OpType.ALU) {
        io.res.bits.gprResult := alu.io.output
      }
      is(OpType.LUI) {
        io.res.bits.gprResult := activeUop.imm
      }
      is(OpType.AUIPC) {
        io.res.bits.gprResult := activeUop.pc + activeUop.imm
      }
      is(OpType.LOAD) {
        io.res.bits.memAddress := io.regFile.readData1 + activeUop.imm
      }
      is(OpType.STORE) {
        io.res.bits.memAddress := io.regFile.readData1 + activeUop.imm
        io.res.bits.storeData := io.regFile.readData2
      }

      is(OpType.BRANCH) {
        val rs1 = io.regFile.readData1
        val rs2 = io.regFile.readData2
        // Compute branch condition based on funct3
        val taken = WireDefault(false.B)

        switch(activeUop.branchFunc) {
          is(BranchOp.BEQ) {
            taken := (rs1 === rs2)
          }
          is(BranchOp.BNE) {
            taken := (rs1 =/= rs2)
          }
          is(BranchOp.BLT) {
            taken := (rs1.asSInt < rs2.asSInt)
          }
          is(BranchOp.BGE) {
            taken := (rs1.asSInt >= rs2.asSInt)
          }
          is(BranchOp.BLTU) {
            taken := (rs1 < rs2)
          }
          is(BranchOp.BGEU) {
            taken := (rs1 >= rs2)
          }
        }

        io.branch.valid := taken
        needFlush := taken
        io.branch.bits.targetPC := activeUop.pc + activeUop.imm
      }

      is(OpType.JAL) {
        // Unconditional jump
        io.branch.valid := true.B
        io.branch.bits.targetPC := activeUop.pc + activeUop.imm
        io.res.bits.gprResult := activeUop.pc + 4.U // Save return address
      }

      is(OpType.JALR) {
        // Indirect jump
        io.branch.valid := true.B
        val target = io.regFile.readData1 + activeUop.imm
        io.branch.bits.targetPC := Cat(target(31, 1), 0.U(1.W)) // Clear LSB
        io.res.bits.gprResult := activeUop.pc + 4.U // Save return address
      }

      is(OpType.MUL) {
        mul.foreach { mul =>
          io.res.bits.gprResult := mul.io.result.bits
        }
      }

      is(OpType.DIV) {
        div.foreach { div =>
          io.res.bits.gprResult := div.io.result.bits
        }
      }

      is(OpType.CSRRW, OpType.CSRRS, OpType.CSRRC) {
        // CSR operations - result goes to GPR, write signal propagates to writeback
        io.res.bits.gprResult := csr.io.result.bits
        io.res.bits.csrWrite := csr.io.csrWrite.valid
        io.res.bits.csrResult := csr.io.csrWrite.bits
      }

      is(OpType.INVALID) {
        // Illegal instruction trap
        // Read mtvec to get trap handler address
        io.csrFile.valid := true.B
        io.csrFile.addr := 0x305.U  // mtvec
        val mtvecValue = io.csrFile.data

        // Generate redirect to trap handler (like a branch)
        io.branch.valid := true.B
        io.branch.bits.targetPC := mtvecValue
        needFlush := true.B

        // OpType.INVALID + PC + instruction will flow to Writeback
        // where trap CSRs (mepc, mcause, mtval) will be written
      }

      is(OpType.MRET) {
        // Return from machine trap
        // Read mepc to get return address
        io.csrFile.valid := true.B
        io.csrFile.addr := 0x341.U  // mepc
        val mepcValue = io.csrFile.data

        // Generate redirect to return address
        io.branch.valid := true.B
        io.branch.bits.targetPC := mepcValue
        needFlush := true.B
      }
    }
  }
}
