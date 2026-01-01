package svarog.bits

import chisel3._
import chisel3.util._
import svarog.memory.MemWidth

object MemoryUtils {

  /** Compute word-aligned address and byte offset from a byte address
    *
    * @param byteAddr
    *   The byte-aligned address
    * @param wordSize
    *   Size of a word in bytes (xlen / 8)
    * @return
    *   Tuple of (word-aligned address, byte offset within word)
    */
  def alignAddress(byteAddr: UInt, wordSize: Int): (UInt, UInt) = {
    val offsetWidth = log2Ceil(wordSize)
    val wordAlignedAddr = (byteAddr / wordSize.U) * wordSize.U
    val wordOffset = byteAddr(offsetWidth - 1, 0)
    (wordAlignedAddr, wordOffset)
  }

  /** Generate a shifted mask for memory requests
    *
    * @param memWidth
    *   The memory access width (BYTE, HALF, WORD, DWORD)
    * @param wordOffset
    *   The byte offset within the word
    * @param xlen
    *   The width of the data path in bits
    * @return
    *   A shifted mask vector
    */
  def generateShiftedMask(
      memWidth: MemWidth.Type,
      wordOffset: UInt,
      xlen: Int
  ): Vec[Bool] = {
    val wordSize = xlen / 8
    val offsetWidth = log2Ceil(wordSize)
    val baseMask = MemWidth.mask(xlen)(memWidth)

    val shiftedMask = Wire(Vec(wordSize, Bool()))
    for (j <- 0 until wordSize) {
      val jWide = j.U(32.W)
      val offsetWide = jWide - wordOffset
      val offset = offsetWide(offsetWidth - 1, 0)
      shiftedMask(j) := Mux(
        (j.U >= wordOffset) && (offset < baseMask.length.U),
        baseMask(offset),
        false.B
      )
    }
    shiftedMask
  }

  /** Shift write data to align with word boundary
    *
    * @param dataBytes
    *   The unshifted write data as a vector of bytes
    * @param wordOffset
    *   The byte offset within the word
    * @param wordSize
    *   Size of a word in bytes (xlen / 8)
    * @return
    *   The shifted write data
    */
  def shiftWriteData(
      dataBytes: Vec[UInt],
      wordOffset: UInt,
      wordSize: Int
  ): Vec[UInt] = {
    val offsetWidth = log2Ceil(wordSize)
    val shiftedWriteData = Wire(Vec(wordSize, UInt(8.W)))
    for (j <- 0 until wordSize) {
      val jWide = j.U(32.W)
      val offsetWide = jWide - wordOffset
      val offset = offsetWide(offsetWidth - 1, 0)
      shiftedWriteData(j) := Mux(
        (j.U >= wordOffset) && (offset < dataBytes.length.U),
        dataBytes(offset),
        0.U
      )
    }
    shiftedWriteData
  }

  /** Unshift read data from word-aligned memory response
    *
    * @param dataBytes
    *   The word-aligned read data from memory
    * @param wordOffset
    *   The byte offset that was used in the request
    * @param wordSize
    *   Size of a word in bytes (xlen / 8)
    * @return
    *   The unshifted read data
    */
  def unshiftReadData(
      dataBytes: Vec[UInt],
      wordOffset: UInt,
      wordSize: Int
  ): Vec[UInt] = {
    val shiftedBytes = Wire(Vec(wordSize, UInt(8.W)))
    for (j <- 0 until wordSize) {
      val readValue = Wire(UInt(8.W))
      readValue := 0.U
      for (offset <- 0 until wordSize) {
        when(wordOffset === offset.U) {
          val targetIdx = (offset + j) % wordSize
          readValue := dataBytes(targetIdx)
        }
      }
      shiftedBytes(j) := readValue
    }
    shiftedBytes
  }

  /** Generate a full-word mask (all bytes enabled)
    *
    * @param wordSize
    *   Size of a word in bytes (xlen / 8)
    * @return
    *   A mask vector with all bits set to true
    */
  def fullWordMask(wordSize: Int): Vec[Bool] = {
    VecInit(Seq.fill(wordSize)(true.B))
  }
}

object masked {
  def apply[T <: Data](data: Vec[T], mask: Vec[Bool]): Vec[T] = {
    VecInit(data.zip(mask).map { case (d, m) =>
      Mux(m, d, 0.U.asTypeOf(d))
    })
  }
}

object asLE {
  def apply(in: UInt): Vec[UInt] = {
    val numBytes = in.getWidth / 8;
    val out = Wire(Vec(numBytes, UInt(8.W)))

    for (i <- 0 until numBytes) {
      out(i) := in(8 * (i + 1) - 1, 8 * i)
    }

    out
  }
}
