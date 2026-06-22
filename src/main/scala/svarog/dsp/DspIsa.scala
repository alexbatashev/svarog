package svarog.dsp

import chisel3._
import chisel3.util._

/** Parameters for the VLIW DSP prototype.
  *
  * The DSP issues one "very long instruction word" (a bundle) per cycle. A
  * bundle is made of [[numLanes]] independent 16-bit operation slots, so the
  * full word is `numLanes * 16` bits wide. Each lane reads two source DSP
  * registers and writes one destination register, all in parallel.
  *
  * @param xlen
  *   data path width of each DSP register (matches the host core, e.g. 32)
  * @param numLanes
  *   number of parallel operation slots packed into one bundle
  * @param numRegs
  *   number of DSP registers (must be <= 16 so a register fits in 4 bits)
  */
case class DspParams(
    xlen: Int = 32,
    numLanes: Int = 4,
    numRegs: Int = 16
) {
  require(xlen >= 8, "xlen must be at least 8")
  require(numLanes >= 1, "need at least one lane")
  require(numRegs >= 2 && numRegs <= 16, "numRegs must be in 2..16")

  /** Bits needed to address a DSP register (fixed 4 by the encoding). */
  val regBits: Int = 4

  /** Bits in a single lane: op(4) + rd(4) + rs1(4) + rs2(4). */
  val laneBits: Int = DspOp.width + 3 * regBits

  /** Total width of one VLIW bundle. */
  val bundleBits: Int = numLanes * laneBits
}

/** Operation selector carried in each lane.
  *
  * Encoded in the top 4 bits of a lane. Only ADD/SUB/MUL do anything; NOP lets
  * the scheduler leave a slot empty (its destination register is untouched).
  * The field is intentionally 4 bits wide to leave room for future ops.
  */
object DspOp {
  val width: Int = 4

  val NOP: Int = 0
  val ADD: Int = 1
  val SUB: Int = 2
  val MUL: Int = 3
}

/** Decoded view of one lane.
  *
  * Bit layout within a 16-bit lane (MSB first):
  * {{{
  *   [15:12] op   [11:8] rd   [7:4] rs1   [3:0] rs2
  * }}}
  * Chisel packs the first bundle field into the most-significant bits, so
  * `lane.asUInt` reproduces exactly that layout.
  */
class DspLane extends Bundle {
  val op = UInt(DspOp.width.W)
  val rd = UInt(4.W)
  val rs1 = UInt(4.W)
  val rs2 = UInt(4.W)
}

/** Tiny assembler for building DSP bundles from Scala/tests.
  *
  * A bundle is laid out little-lane-first: lane 0 occupies the least
  * significant 16 bits, lane 1 the next 16 bits, and so on. That matches how
  * Chisel reinterprets the word as `Vec(numLanes, DspLane)`.
  *
  * Example:
  * {{{
  *   // lane0: r3 = r1 + r2,  lane1: r4 = r1 - r2,  lane2: r5 = r1 * r2
  *   val word = DspAsm.bundle(
  *     DspAsm.add(rd = 3, rs1 = 1, rs2 = 2),
  *     DspAsm.sub(rd = 4, rs1 = 1, rs2 = 2),
  *     DspAsm.mul(rd = 5, rs1 = 1, rs2 = 2)
  *   )
  * }}}
  */
object DspAsm {
  private def field(v: Int, name: String): BigInt = {
    require(v >= 0 && v < 16, s"$name=$v out of range 0..15")
    BigInt(v)
  }

  /** Encode a single 16-bit lane. */
  def lane(op: Int, rd: Int, rs1: Int, rs2: Int): BigInt =
    (field(op, "op") << 12) | (field(rd, "rd") << 8) |
      (field(rs1, "rs1") << 4) | field(rs2, "rs2")

  def nop: BigInt = lane(DspOp.NOP, 0, 0, 0)
  def add(rd: Int, rs1: Int, rs2: Int): BigInt = lane(DspOp.ADD, rd, rs1, rs2)
  def sub(rd: Int, rs1: Int, rs2: Int): BigInt = lane(DspOp.SUB, rd, rs1, rs2)
  def mul(rd: Int, rs1: Int, rs2: Int): BigInt = lane(DspOp.MUL, rd, rs1, rs2)

  /** Pack lanes (lane 0 first) into one VLIW bundle word. Missing lanes are
    * filled with NOPs.
    */
  def bundle(lanes: BigInt*): BigInt =
    lanes.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (laneVal, idx)) =>
      acc | (laneVal << (idx * 16))
    }
}
