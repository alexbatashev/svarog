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
import svarog.bits.{CSREx, CSRReadIO}

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
}

class BranchFeedback(xlen: Int) extends Bundle {
  val targetPC = Output(UInt(xlen.W))
}

class Execute(xlen: Integer) extends Module {
  val io = IO(new Bundle {
    val uop = Flipped(Decoupled(new MicroOp(xlen)))
    val res = Decoupled(new ExecuteResult(xlen))
    val branch = Valid(new BranchFeedback(xlen))

    val regFile = Flipped(new RegFileReadIO(xlen))
    val csrFile = new Bundle {
      val read = Flipped(new CSRReadIO())
    }

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
  // val mul = Module(new SimpleMultiplier(xlen))
  // val div = Module(new SimpleDivider(xlen))
  val csr = Module(new CSREx(xlen))

  // Single-cycle execute: current instruction always completes
  // Output is valid only when we have an instruction AND (downstream is ready AND not stalled)
  // This ensures we don't output the same instruction multiple times
  val canDequeue = io.res.ready && !io.stall
  io.uop.ready := canDequeue
  io.res.valid := io.uop.valid && canDequeue

  io.hazard.valid := io.uop.valid && io.uop.bits.regWrite && !(io.uop.bits.rd === 0.U)
  io.hazard.bits := io.uop.bits.rd

  io.csrHazard.valid := io.uop.valid && (
    io.uop.bits.opType === OpType.CSRRW ||
      io.uop.bits.opType === OpType.CSRRS ||
      io.uop.bits.opType === OpType.CSRRC
  )
  io.csrHazard.bits.addr := io.uop.bits.csrAddr
  io.csrHazard.bits.isWrite := csr.io.csr.write.en

  io.res.bits.opType := io.uop.bits.opType
  io.res.bits.pc := io.uop.bits.pc

  // ALU result
  io.res.bits.gprResult := 0.U

  // Branch/jump target address
  io.branch.bits.targetPC := 0.U
  io.branch.valid := false.B

  // Load/store address
  io.res.bits.memAddress := 0.U
  io.res.bits.memWidth := io.uop.bits.memWidth
  io.res.bits.memUnsigned := io.uop.bits.memUnsigned

  // Store data
  io.res.bits.storeData := 0.U

  io.res.bits.rd := io.uop.bits.rd
  io.res.bits.gprWrite := io.uop.bits.regWrite

  // CSR defaults
  io.res.bits.csrAddr := io.uop.bits.csrAddr
  io.res.bits.csrWrite := false.B
  io.res.bits.csrResult := 0.U

  io.regFile.readAddr1 := io.uop.bits.rs1
  io.regFile.readAddr2 := io.uop.bits.rs2

  alu.io.op := io.uop.bits.aluOp
  alu.io.input1 := io.regFile.readData1
  alu.io.input2 := Mux(
    io.uop.bits.hasImm,
    io.uop.bits.imm,
    io.regFile.readData2
  )

  // Multiplier wiring
  // mul.io.inp.bits.op := io.uop.bits.mulOp
  // mul.io.inp.bits.multiplicant := io.regFile.readData1
  // mul.io.inp.bits.multiplier := io.regFile.readData2
  // mul.io.inp.valid := io.uop.valid && (io.uop.bits.opType === OpType.MUL)

  // Divider wiring
  // div.io.inp.bits.op := io.uop.bits.divOp
  // div.io.inp.bits.dividend := io.regFile.readData1
  // div.io.inp.bits.divisor := io.regFile.readData2
  // div.io.inp.valid := io.uop.valid && (io.uop.bits.opType === OpType.DIV)

  // CSR wiring
  csr.io.uop.valid := io.uop.valid
  csr.io.uop.bits := io.uop.bits
  csr.io.rs1Value := io.regFile.readData1

  io.csrFile.read <> csr.io.csr.read

  when(io.uop.valid && !needFlush) {
    switch(io.uop.bits.opType) {
      is(OpType.ALU) {
        io.res.bits.gprResult := alu.io.output
      }
      is(OpType.LUI) {
        io.res.bits.gprResult := io.uop.bits.imm
      }
      is(OpType.AUIPC) {
        io.res.bits.gprResult := io.uop.bits.pc + io.uop.bits.imm
      }
      is(OpType.LOAD) {
        io.res.bits.memAddress := io.regFile.readData1 + io.uop.bits.imm
      }
      is(OpType.STORE) {
        io.res.bits.memAddress := io.regFile.readData1 + io.uop.bits.imm
        io.res.bits.storeData := io.regFile.readData2
      }

      is(OpType.BRANCH) {
        val rs1 = io.regFile.readData1
        val rs2 = io.regFile.readData2
        // Compute branch condition based on funct3
        val taken = WireDefault(false.B)

        switch(io.uop.bits.branchFunc) {
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
        io.branch.bits.targetPC := io.uop.bits.pc + io.uop.bits.imm
      }

      is(OpType.JAL) {
        // Unconditional jump
        io.branch.valid := true.B
        io.branch.bits.targetPC := io.uop.bits.pc + io.uop.bits.imm
        io.res.bits.gprResult := io.uop.bits.pc + 4.U // Save return address
      }

      is(OpType.JALR) {
        // Indirect jump
        io.branch.valid := true.B
        val target = io.regFile.readData1 + io.uop.bits.imm
        io.branch.bits.targetPC := Cat(target(31, 1), 0.U(1.W)) // Clear LSB
        io.res.bits.gprResult := io.uop.bits.pc + 4.U // Save return address
      }

      // is(OpType.MUL) {
      //   // Multiplication operations
      //   io.res.bits.gprResult := mul.io.result.bits
      // }

      // is(OpType.DIV) {
      //   // Division/remainder operations
      //   io.res.bits.gprResult := div.io.result.bits
      // }

      is(OpType.CSRRW, OpType.CSRRS, OpType.CSRRC) {
        // CSR operations - result goes to GPR, write signal propagates to writeback
        io.res.bits.gprResult := csr.io.result.bits
        io.res.bits.csrWrite := csr.io.csr.write.en
        io.res.bits.csrResult := csr.io.csr.write.data
      }
    }
  }
}
