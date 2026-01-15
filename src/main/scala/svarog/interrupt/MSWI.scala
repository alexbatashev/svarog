package svarog.interrupt

import chisel3._
import chisel3.util._
import svarog.memory.WishboneSlave
import svarog.memory.WishboneIO

/** ACLINT MSWI (Machine Software Interrupt) Device
  *
  * Provides memory-mapped registers for triggering machine-level software
  * interrupts (MSI) on each hart. This enables inter-processor interrupts (IPI)
  * in multi-hart systems.
  *
  * Memory map (per ACLINT spec):
  * - Offset 0x0000 + (hart_id * 4): msip[hart_id] register (32-bit)
  *
  * Only bit 0 of each msip register is significant:
  * - Write 1: Set MSIP for that hart
  * - Write 0: Clear MSIP for that hart
  * - Read: Returns current MSIP state
  *
  * @param numHarts Number of harts in the system
  * @param xlen Address width
  * @param maxReqWidth Data width for Wishbone bus
  * @param baseAddr Base address for MSWI registers
  */
class MSWI(numHarts: Int, xlen: Int, maxReqWidth: Int, baseAddr: Long) extends Module with WishboneSlave {
  override val io: WishboneIO = IO(Flipped(new WishboneIO(xlen, maxReqWidth)))

  override def addrStart: Long = baseAddr

  // Each hart has a 4-byte msip register
  override def addrEnd: Long = baseAddr + numHarts * 4

  // Output signals to each hart's interrupter
  val msip = IO(Output(Vec(numHarts, Bool())))

  // Internal msip registers
  val msipRegs = RegInit(VecInit(Seq.fill(numHarts)(false.B)))

  // Connect registers to output
  msip := msipRegs

  // Default outputs
  io.ack := false.B
  io.stall := false.B
  io.dataToMaster := 0.U
  io.error := false.B

  // Address decoding
  val offset = io.addr - baseAddr.U
  val hartId = offset >> 2  // Divide by 4 to get hart index
  val validHart = hartId < numHarts.U

  when(io.cycleActive && io.strobe) {
    io.ack := true.B

    when(validHart) {
      when(io.writeEnable) {
        // Write: set or clear msip based on bit 0
        msipRegs(hartId) := io.dataToSlave(0)
      }.otherwise {
        // Read: return current msip state (zero-extended)
        io.dataToMaster := Cat(0.U((maxReqWidth - 1).W), msipRegs(hartId))
      }
    }.otherwise {
      // Access to invalid hart - return error
      io.error := true.B
    }
  }
}

object MSWI {
  /** Create an MSWI device and connect it to hart interrupters
    *
    * @param numHarts Number of harts
    * @param xlen Address width
    * @param maxReqWidth Data width
    * @param baseAddr Base address
    * @return The MSWI module instance
    */
  def apply(numHarts: Int, xlen: Int, maxReqWidth: Int, baseAddr: Long): MSWI = {
    Module(new MSWI(numHarts, xlen, maxReqWidth, baseAddr))
  }
}
