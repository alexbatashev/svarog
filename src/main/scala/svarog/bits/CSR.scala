package svarog.bits

import chisel3._
import chisel3.util._
import svarog.decoder.MicroOp
import svarog.decoder.OpType

class ControlRegisterIO(width: Int) extends Bundle {
  val read = Output(UInt(width.W))
  val write = Input(Valid(UInt(width.W)))
}

trait ControlRegisterLike {
  def address: Int
  def width: Int
  def readOnly: Boolean
  val io: ControlRegisterIO
}

class ConstantControlRegister(
    val address: Int,
    val width: Int = 32,
    initialValue: Int = 0,
    val readOnly: Boolean = true
) extends Module
    with ControlRegisterLike {

  val io = IO(new ControlRegisterIO(width))

  private val data = RegInit(initialValue.U(width.W))

  io.read := data

  when(io.write.valid && !readOnly.B) {
    data := io.write.bits
  }
}

object ConstantControlRegister {

  /** Returns constant registers required by Zicsr spec
    *
    * @param hartId
    *   current registers' Hart ID
    * @return
    *   a sequence of register-like objects
    */
  def getDefaultRegisters(hartId: Int): Seq[() => ControlRegisterLike] = {
    Seq(
      () => new ConstantControlRegister(0x301), // misa, empty for now
      () => new ConstantControlRegister(0xf11), // mvendorid, 0 for non-commercial
      () => new ConstantControlRegister(0xf12), // marchid, 0 for now
      () => new ConstantControlRegister(0xf13), // mimpid, 0 for now
      () =>
        new ConstantControlRegister(
          0xf14,
          initialValue = hartId
        ) // mhartid
    )
  }
}

class CSRReadIO extends Bundle {
  val addr = Input(UInt(12.W))
  val data = Output(UInt(64.W))
}

class CSRWriteIO extends Bundle {
  val en = Input(Bool())
  val addr = Input(UInt(12.W))
  val data = Input(UInt(64.W))
}

class CSRIO extends Bundle {
  val read = new CSRReadIO()
  val write = new CSRWriteIO()
}

class CSRFile(regGens: Seq[() => ControlRegisterLike]) extends Module {
  val io = IO(new CSRIO())

  io.read.data := 0.U

  private val regs = regGens.map(gen => Module(gen()))

  regs.foreach { reg =>
    reg.io.write.valid := false.B
    reg.io.write.bits := 0.U(reg.width.W)

    when(io.read.addr === reg.address.U) {
      io.read.data := reg.io.read
    }

    if (!reg.readOnly) {
      when(io.write.en && io.write.addr === reg.address.U) {
        reg.io.write.valid := true.B
        reg.io.write.bits := io.write.data(reg.width - 1, 0)
      }
    }
  }
}

class CSREx(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val uop = Input(Valid(new MicroOp(xlen)))
    val rs1Value = Input(UInt(xlen.W)) // Register value from Execute stage
    val result = Output(Valid(UInt(xlen.W)))
    val csr = Flipped(new CSRIO())
  })

  // Default outputs
  io.result.valid := false.B
  io.result.bits := 0.U
  io.csr.read.addr := 0.U
  io.csr.write.en := false.B
  io.csr.write.addr := 0.U
  io.csr.write.data := 0.U

  val isCSR =
    io.uop.bits.opType === OpType.CSRRW || io.uop.bits.opType === OpType.CSRRS || io.uop.bits.opType === OpType.CSRRC
  when(io.uop.valid && isCSR) {
    // Read CSR address from uop
    io.csr.read.addr := io.uop.bits.csrAddr

    // Get the value to use for modification (either from rs1 or immediate)
    val modifyValue = Wire(UInt(xlen.W))
    when(io.uop.bits.hasImm) {
      // For immediate forms (CSRRWI, CSRRSI, CSRRCI), use zero-extended immediate
      modifyValue := io.uop.bits.imm
    }.otherwise {
      // For register forms, use the rs1 value passed from Execute stage
      modifyValue := io.rs1Value
    }

    // Execute CSR operation based on opType
    when(io.uop.bits.opType === OpType.CSRRW) {
      // CSRRW: Atomic Read/Write
      // Returns old CSR value, writes new value unconditionally
      io.result.valid := true.B
      io.result.bits := io.csr.read.data

      io.csr.write.en := true.B
      io.csr.write.addr := io.uop.bits.csrAddr
      io.csr.write.data := modifyValue
    }.elsewhen(io.uop.bits.opType === OpType.CSRRS) {
      // CSRRS: Atomic Read and Set Bits
      // Returns old CSR value, sets bits where rs1/imm has 1s
      io.result.valid := true.B
      io.result.bits := io.csr.read.data

      // Only write if rs1/imm is non-zero
      when(modifyValue =/= 0.U) {
        io.csr.write.en := true.B
        io.csr.write.addr := io.uop.bits.csrAddr
        io.csr.write.data := io.csr.read.data | modifyValue
      }
    }.elsewhen(io.uop.bits.opType === OpType.CSRRC) {
      // CSRRC: Atomic Read and Clear Bits
      // Returns old CSR value, clears bits where rs1/imm has 1s
      io.result.valid := true.B
      io.result.bits := io.csr.read.data

      // Only write if rs1/imm is non-zero
      when(modifyValue =/= 0.U) {
        io.csr.write.en := true.B
        io.csr.write.addr := io.uop.bits.csrAddr
        io.csr.write.data := io.csr.read.data & ~modifyValue
      }
    }
  }
}
