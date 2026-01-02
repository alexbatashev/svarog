package svarog.bits

import chisel3._
import chisel3.experimental.BaseModule
import chisel3.util._
import svarog.decoder.MicroOp
import svarog.decoder.OpType

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
      readMaster: Valid[CSRDeviceReadIO],
      writeMaster: Valid[CSRDeviceWriteIO],
      devices: Seq[CSRBusDevice]
  ): Unit = {
    devices.foreach { dev =>
      dev.bus.read.valid := false.B
      dev.bus.read.bits := DontCare

      dev.bus.write.valid := false.B
      dev.bus.write.bits := DontCare

      when(readMaster.valid) {
        when(dev.addrInRange(readMaster.bits.addr)) {
          dev.bus.read <> readMaster
        }
      }

      when(writeMaster.valid) {
        when(dev.addrInRange(writeMaster.bits.addr)) {
          dev.bus.write <> writeMaster
        }
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
    val csr = Flipped(Valid(new CSRDeviceReadIO(xlen)))
  })

  // Default outputs
  io.result.valid := false.B
  io.result.bits := 0.U
  io.csr.bits.addr := 0.U

  val isCSR =
    io.uop.bits.opType === OpType.CSRRW || io.uop.bits.opType === OpType.CSRRS || io.uop.bits.opType === OpType.CSRRC
  when(io.uop.valid && isCSR) {
    // Read CSR address from uop
    io.csr.read.addr := io.uop.bits.csrAddr
    io.csr.read.en := true.B

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
