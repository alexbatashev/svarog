package svarog.micro

import chisel3._
import chisel3.util._
import svarog.bits.RegFileReadIO
import svarog.bits.ALU
import svarog.decoder.BranchOp
import svarog.decoder.{MicroOp, OpType}
import svarog.memory.MemWidth

class ExecuteIO(xlen: Int) extends Bundle {
  val uop = Flipped(new MicroOp(xlen))
  val regFile = Flipped(new RegFileReadIO(xlen))

  // Control signal for other stages
  val opType = Output(OpType())

  val rd = Output(UInt(5.W))

  val regWrite = Output(Bool())

  // ALU result
  val intResult = Output(UInt(xlen.W))
  // Branch/jump target address
  val targetPC = Output(UInt(xlen.W))
  val branchTaken = Output(Bool())
  // Load/store address
  val memAddress = Output(UInt(xlen.W))
  val memWidth = Output(MemWidth())
  val memUnsigned = Output(Bool())

  val storeData = Output(UInt(xlen.W))
}

class Execute(xlen: Integer) extends Module {
  val io = IO(new ExecuteIO(xlen))
  val alu = Module(new ALU(xlen))

  io.opType := io.uop.opType

  // ALU result
  io.intResult := 0.U

  // Branch/jump target address
  io.targetPC := 0.U
  io.branchTaken := false.B

  // Load/store address
  io.memAddress := 0.U
  io.memWidth := io.uop.memWidth
  io.memUnsigned := io.uop.memUnsigned

  // Store data
  io.storeData := 0.U

  io.rd := io.uop.rd
  io.regWrite := io.uop.regWrite

  io.regFile.readAddr1 := io.uop.rs1
  io.regFile.readAddr2 := io.uop.rs2

  alu.io.op := io.uop.aluOp
  alu.io.input1 := io.regFile.readData1
  alu.io.input2 := Mux(io.uop.hasImm, io.uop.imm, io.regFile.readData2)

  val branchDebugCounter = RegInit(0.U(8.W))
  val aluDebugCounter = RegInit(0.U(8.W))

  switch(io.uop.opType) {
    is(OpType.ALU) {
      io.intResult := alu.io.output
    }
    is(OpType.LUI) {
      io.intResult := io.uop.imm
    }
    is(OpType.AUIPC) {
      io.intResult := io.uop.pc + io.uop.imm
    }
    is(OpType.LOAD) {
      io.memAddress := io.regFile.readData1 + io.uop.imm
    }
    is(OpType.STORE) {
      io.memAddress := io.regFile.readData1 + io.uop.imm
      io.storeData := io.regFile.readData2
    }

    is(OpType.BRANCH) {
      val rs1 = io.regFile.readData1
      val rs2 = io.regFile.readData2
      // Compute branch condition based on funct3
      val taken = WireDefault(false.B)

      switch(io.uop.branchFunc) {
        is("b000".U) { // BEQ
          taken := (rs1 === rs2)
        }
        is("b001".U) { // BNE
          taken := (rs1 =/= rs2)
        }
        is("b100".U) { // BLT
          taken := (rs1.asSInt < rs2.asSInt)
        }
        is("b101".U) { // BGE
          taken := (rs1.asSInt >= rs2.asSInt)
        }
        is("b110".U) { // BLTU
          taken := (rs1 < rs2)
        }
        is("b111".U) { // BGEU
          taken := (rs1 >= rs2)
        }
      }

      io.branchTaken := taken
      io.targetPC := io.uop.pc + io.uop.imm
    }

    is(OpType.JAL) {
      // Unconditional jump
      io.branchTaken := true.B
      io.targetPC := io.uop.pc + io.uop.imm
      io.intResult := io.uop.pc + 4.U // Save return address
    }

    is(OpType.JALR) {
      // Indirect jump
      io.branchTaken := true.B
      val target = io.regFile.readData1 + io.uop.imm
      io.targetPC := Cat(target(31, 1), 0.U(1.W)) // Clear LSB
      io.intResult := io.uop.pc + 4.U // Save return address
    }
  }
}
