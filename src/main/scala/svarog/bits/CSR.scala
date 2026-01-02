package svarog.bits

import chisel3._
import chisel3.experimental.BaseModule
import chisel3.util._
import svarog.decoder.MicroOp
import svarog.decoder.OpType

class CSRReadMasterIO(xlen: Int) extends Bundle {
  val valid = Input(Bool())
  val addr = Input(UInt(12.W))
  val data = Output(UInt(xlen.W))
}

class CSRWriteMasterIO(xlen: Int) extends Bundle {
  val valid = Input(Bool())
  val addr = Input(UInt(12.W))
  val data = Input(UInt(xlen.W))
  val error = Output(Bool())
}

class CSRDeviceReadIO(xlen: Int) extends Bundle {
  val addr = Input(UInt(12.W))
  val data = Output(UInt(xlen.W))
}

class CSRDeviceWriteIO(xlen: Int) extends Bundle {
  val addr = Input(UInt(12.W))
  val data = Input(UInt(xlen.W))
  val error = Output(Bool())
}

class CSRDeviceIO(xlen: Int) extends Bundle {
  val read = Valid(new CSRDeviceReadIO(xlen))
  val write = Valid(new CSRDeviceWriteIO(xlen))
}

trait CSRBusDevice {
  def bus: CSRDeviceIO
  def addrInRange(addr: UInt): Bool
}

object CSRBus {
  def apply(
      readMaster: CSRReadMasterIO,
      writeMaster: CSRWriteMasterIO,
      devices: Seq[CSRBusDevice]
  ): Unit = {
    readMaster.data := 0.U
    writeMaster.error := false.B

    devices.foreach { dev =>
      dev.bus.read.valid := false.B
      dev.bus.read.bits.addr := 0.U

      dev.bus.write.valid := false.B
      dev.bus.write.bits.addr := 0.U
      dev.bus.write.bits.data := 0.U

      when(readMaster.valid && dev.addrInRange(readMaster.addr)) {
        dev.bus.read.valid := true.B
        dev.bus.read.bits.addr := readMaster.addr
      }

      when(writeMaster.valid && dev.addrInRange(writeMaster.addr)) {
        dev.bus.write.valid := true.B
        dev.bus.write.bits.addr := writeMaster.addr
        dev.bus.write.bits.data := writeMaster.data
      }
    }

    devices.foreach { dev =>
      when(dev.bus.read.valid) {
        readMaster.data := dev.bus.read.bits.data
      }
      when(dev.bus.write.valid) {
        writeMaster.error := dev.bus.write.bits.error
      }
    }
  }
}

class ConstantCsrDevice(
    address: Int,
    xlen: Int = 32,
    width: Int = 32,
    initialValue: Int = 0,
    readOnly: Boolean = true
) extends Module
    with CSRBusDevice {

  require(width <= xlen, "Register must not be wider than xlen")

  val bus = IO(new CSRDeviceIO(xlen))

  def addrInRange(addr: UInt): Bool = addr === address.U

  val value = RegInit(initialValue.U(width.W))

  bus.read.bits.data := 0.U
  bus.write.bits.error := false.B

  when(bus.read.valid) {
    bus.read.bits.data(width - 1, 0) := value
  }

  when(bus.write.valid) {
    if (readOnly) {
      bus.write.bits.error := true.B
    } else {
      value := bus.write.bits.data(width - 1, 0)
    }
  }
}

object ConstantCsrDevice {

  /** Returns constant registers required by Zicsr spec
    *
    * @param hartId
    *   current registers' Hart ID
    * @return
    *   a sequence of register-like objects
    */
  def getDefaultRegisters(
      hartId: Int
  ): Seq[() => (BaseModule with CSRBusDevice)] = {
    Seq(
      () => new ConstantCsrDevice(0x301), // misa, empty for now
      () => new ConstantCsrDevice(0xf11), // mvendorid, 0 for non-commercial
      () => new ConstantCsrDevice(0xf12), // marchid, 0 for now
      () => new ConstantCsrDevice(0xf13), // mimpid, 0 for now
      () =>
        new ConstantCsrDevice(
          0xf14,
          initialValue = hartId
        ) // mhartid
    )
  }
}

class CSREx(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val uop = Input(Valid(new MicroOp(xlen)))
    val rs1Value = Input(UInt(xlen.W)) // Register value from Execute stage
    val result = Output(Valid(UInt(xlen.W)))
    val csr = Flipped(new CSRReadMasterIO(xlen))
    val csrWrite = Output(Valid(UInt(xlen.W)))
  })

  // Default outputs
  io.result.valid := false.B
  io.result.bits := 0.U
  io.csr.valid := false.B
  io.csr.addr := 0.U
  io.csrWrite.valid := false.B
  io.csrWrite.bits := 0.U

  val isCSR =
    io.uop.bits.opType === OpType.CSRRW || io.uop.bits.opType === OpType.CSRRS || io.uop.bits.opType === OpType.CSRRC
  when(io.uop.valid && isCSR) {
    // Read CSR address from uop
    io.csr.valid := true.B
    io.csr.addr := io.uop.bits.csrAddr

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
      io.result.bits := io.csr.data

      io.csrWrite.valid := true.B
      io.csrWrite.bits := modifyValue
    }.elsewhen(io.uop.bits.opType === OpType.CSRRS) {
      // CSRRS: Atomic Read and Set Bits
      // Returns old CSR value, sets bits where rs1/imm has 1s
      io.result.valid := true.B
      io.result.bits := io.csr.data

      // Only write if rs1/imm is non-zero
      when(modifyValue =/= 0.U) {
        io.csrWrite.valid := true.B
        io.csrWrite.bits := io.csr.data | modifyValue
      }
    }.elsewhen(io.uop.bits.opType === OpType.CSRRC) {
      // CSRRC: Atomic Read and Clear Bits
      // Returns old CSR value, clears bits where rs1/imm has 1s
      io.result.valid := true.B
      io.result.bits := io.csr.data

      // Only write if rs1/imm is non-zero
      when(modifyValue =/= 0.U) {
        io.csrWrite.valid := true.B
        io.csrWrite.bits := io.csr.data & ~modifyValue
      }
    }
  }
}
