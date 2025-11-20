package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.RegFileReadIO
import svarog.bits.ALU
import svarog.decoder.BranchOp
import svarog.decoder.{MicroOp, OpType}
import svarog.memory.MemWidth

class ExecuteResult(xlen: Int) extends Bundle {
  val opType = Output(OpType())

  val rd = Output(UInt(5.W))
  val regWrite = Output(Bool())

  val intResult = Output(UInt(xlen.W))

  // TODO can we re-use intResult here with a better naming?
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

    // Write register for hazard control
    val hazard = Valid(UInt(5.W))
    val stall = Input(Bool())
  })

  // If the branch is mispredicted on current cycle, whatever instruction
  // comes on next cycle must be dropped
  val needFlush = RegInit(false.B)
  needFlush := false.B // reset at each cycle

  val alu = Module(new ALU(xlen))

  // Single-cycle execute: current instruction always completes
  // Output is valid only when we have an instruction AND (downstream is ready AND not stalled)
  // This ensures we don't output the same instruction multiple times
  val canDequeue = io.res.ready && !io.stall
  io.uop.ready := canDequeue
  io.res.valid := io.uop.valid && canDequeue

  io.hazard.valid := io.uop.valid && io.uop.bits.regWrite && !(io.uop.bits.rd === 0.U)
  io.hazard.bits := io.uop.bits.rd

  io.res.bits.opType := io.uop.bits.opType
  io.res.bits.pc := io.uop.bits.pc

  // ALU result
  io.res.bits.intResult := 0.U

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
  io.res.bits.regWrite := io.uop.bits.regWrite

  io.regFile.readAddr1 := io.uop.bits.rs1
  io.regFile.readAddr2 := io.uop.bits.rs2

  alu.io.op := io.uop.bits.aluOp
  alu.io.input1 := io.regFile.readData1
  alu.io.input2 := Mux(
    io.uop.bits.hasImm,
    io.uop.bits.imm,
    io.regFile.readData2
  )

  when(io.uop.valid && !needFlush) {
    switch(io.uop.bits.opType) {
      is(OpType.ALU) {
        io.res.bits.intResult := alu.io.output
      }
      is(OpType.LUI) {
        io.res.bits.intResult := io.uop.bits.imm
      }
      is(OpType.AUIPC) {
        io.res.bits.intResult := io.uop.bits.pc + io.uop.bits.imm
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
        io.res.bits.intResult := io.uop.bits.pc + 4.U // Save return address
      }

      is(OpType.JALR) {
        // Indirect jump
        io.branch.valid := true.B
        val target = io.regFile.readData1 + io.uop.bits.imm
        io.branch.bits.targetPC := Cat(target(31, 1), 0.U(1.W)) // Clear LSB
        io.res.bits.intResult := io.uop.bits.pc + 4.U // Save return address
      }
    }
  }
}
