package svarog.bits

import chisel3._
import chisel3.util._
import svarog.memory.{WishboneIO, WishboneSlave}
import svarog.util.AsyncQueue
import svarog.util.AsyncQueueParams

/** Constants for timer register offsets */
object TimerConstants {
  val MTIME_LOW_OFFSET = 0x00
  val MTIME_HIGH_OFFSET = 0x04
  val MTIMECMP_LOW_OFFSET = 0x08
  val MTIMECMP_HIGH_OFFSET = 0x0c
  val TIMER_SIZE = 0x10 // 16 bytes total
}

/** Write request to timer core */
class TimerWriteReq extends Bundle {
  val addr = UInt(4.W) // 0x0=mtime, 0x8=mtimecmp
  val data = UInt(64.W) // Full 64-bit value
  val isLow = Bool() // Write low 32 bits only
  val isHigh = Bool() // Write high 32 bits only
}

/** Read request to timer core */
class TimerReadReq extends Bundle {
  val addr = UInt(4.W) // 0x0=mtime, 0x8=mtimecmp
}

/** Timer core interface */
class TimerIO extends Bundle {
  // Write interface (from wrapper)
  val write = Flipped(Decoupled(new TimerWriteReq))

  // Read interface (to wrapper)
  val read = Flipped(Decoupled(new TimerReadReq))
  val readData = Decoupled(UInt(64.W))

  // Direct outputs (for wrapper to read/synchronize)
  val mtime = Output(UInt(64.W))
  val mtimecmp = Output(UInt(64.W))

  // Interrupt (synchronize in wrapper)
  val timerInterrupt = Output(Bool())
}

/** RISC-V Timer Core
  *
  * Standalone timer logic with generic interface. Runs in timer clock domain
  * for monotonic timekeeping.
  *
  * Features:
  *   - 64-bit mtime counter (auto-increments every cycle)
  *   - 64-bit mtimecmp compare register
  *   - Decoupled read/write interface
  *   - Timer interrupt generation
  */
class TimerCore extends Module {
  val io = IO(new TimerIO)

  // 64-bit registers
  val mtime = RegInit(0.U(64.W))
  val mtimecmp = RegInit("hFFFFFFFFFFFFFFFF".U(64.W)) // Start with no interrupt

  // Auto-increment mtime every timer clock cycle
  mtime := mtime + 1.U

  // Handle writes (from AsyncQueue)
  when(io.write.valid) {
    io.write.ready := true.B
    when(io.write.bits.addr === 0x0.U) { // mtime
      when(io.write.bits.isLow) {
        mtime := Cat(mtime(63, 32), io.write.bits.data(31, 0))
      }.elsewhen(io.write.bits.isHigh) {
        mtime := Cat(io.write.bits.data(31, 0), mtime(31, 0))
      }.otherwise {
        mtime := io.write.bits.data
      }
    }.elsewhen(io.write.bits.addr === 0x8.U) { // mtimecmp
      when(io.write.bits.isLow) {
        mtimecmp := Cat(mtimecmp(63, 32), io.write.bits.data(31, 0))
      }.elsewhen(io.write.bits.isHigh) {
        mtimecmp := Cat(io.write.bits.data(31, 0), mtimecmp(31, 0))
      }.otherwise {
        mtimecmp := io.write.bits.data
      }
    }
  }.otherwise {
    io.write.ready := true.B
  }

  // Handle reads (to AsyncQueue)
  when(io.read.valid) {
    io.read.ready := true.B
    io.readData.valid := true.B
    when(io.read.bits.addr === 0x0.U) {
      io.readData.bits := mtime
    }.otherwise {
      io.readData.bits := mtimecmp
    }
  }.otherwise {
    io.read.ready := true.B
    io.readData.valid := false.B
    io.readData.bits := 0.U
  }

  // Expose outputs
  io.mtime := mtime
  io.mtimecmp := mtimecmp
  io.timerInterrupt := mtime >= mtimecmp
}

/** RISC-V Timer Wishbone Wrapper
  *
  * Wraps TimerCore with Wishbone bus interface and clock domain crossing.
  * Implements a 64-bit real-time counter (mtime) and 64-bit compare register
  * (mtimecmp) accessible via Wishbone bus. Generates timer interrupt when mtime
  * >= mtimecmp.
  *
  * Register Map (32-bit bus access to 64-bit registers):
  *   - baseAddr + 0x00: mtime[31:0] (low 32 bits)
  *   - baseAddr + 0x04: mtime[63:32] (high 32 bits)
  *   - baseAddr + 0x08: mtimecmp[31:0] (low 32 bits)
  *   - baseAddr + 0x0C: mtimecmp[63:32] (high 32 bits)
  *
  * Clock Domain Crossing:
  *   - TimerCore runs on timerClock (separate clock domain)
  *   - AsyncQueue handles CDC for read/write requests
  *   - Interrupt synchronized with 2-FF synchronizer
  *
  * Note: Addresses are platform-specific per RISC-V privileged spec. SiFive
  * convention uses 0x02000000 as CLINT base, but this is configurable.
  *
  * @param baseAddr
  *   Base address for timer registers
  * @param addrWidth
  *   Width of address bus (typically 32)
  * @param busWidth
  *   Width of data bus (typically 32)
  */
class TimerWishbone(
    val baseAddr: Long,
    val addrWidth: Int = 32,
    val busWidth: Int = 32
) extends Module
    with WishboneSlave {

  def addrStart: Long = baseAddr
  def addrEnd: Long = baseAddr + TimerConstants.TIMER_SIZE

  val io = IO(Flipped(new WishboneIO(addrWidth, busWidth)))
  val timerInterrupt = IO(Output(Bool()))

  // Timer runs on separate clock
  val timerClock = IO(Input(Clock()))

  // Instantiate timer core in timer clock domain
  val timer = withClock(timerClock) {
    Module(new TimerCore)
  }

  // Clock domain crossing queues
  val writeQueue = Module(
    new AsyncQueue(new TimerWriteReq, new AsyncQueueParams(depth = 2))
  )
  writeQueue.io.enq_clock := clock
  writeQueue.io.enq_reset := reset
  writeQueue.io.deq_clock := timerClock
  writeQueue.io.deq_reset := reset // Assume reset is async
  writeQueue.io.deq <> timer.io.write

  val readReqQueue = Module(
    new AsyncQueue(new TimerReadReq, new AsyncQueueParams(depth = 2))
  )
  readReqQueue.io.enq_clock := clock
  readReqQueue.io.enq_reset := reset
  readReqQueue.io.deq_clock := timerClock
  readReqQueue.io.deq_reset := reset
  readReqQueue.io.deq <> timer.io.read

  val readDataQueue = Module(
    new AsyncQueue(UInt(64.W), new AsyncQueueParams(depth = 2))
  )
  readDataQueue.io.enq_clock := timerClock
  readDataQueue.io.enq_reset := reset
  readDataQueue.io.deq_clock := clock
  readDataQueue.io.deq_reset := reset
  readDataQueue.io.enq <> timer.io.readData

  // Synchronize interrupt (2-stage synchronizer)
  val interruptSync =
    RegNext(RegNext(timer.io.timerInterrupt, false.B), false.B)
  timerInterrupt := interruptSync

  // Wishbone state machine
  val sIdle :: sRead :: sWrite :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val readData = RegInit(0.U(32.W))
  val savedIsLow = RegInit(false.B)
  val savedIsHigh = RegInit(false.B)
  val savedRegAddr = RegInit(0.U(4.W))
  val savedWriteData = RegInit(0.U(64.W))

  // Address decode
  val localAddr = io.addr - baseAddr.U
  val isLowAccess = localAddr(2) === 0.U // Offset 0x00 or 0x08
  val isHighAccess = localAddr(2) === 1.U // Offset 0x04 or 0x0C
  val regAddr = Cat(0.U(3.W), localAddr(3)) // 0x0 or 0x8

  // Default outputs
  io.ack := false.B
  io.stall := false.B
  io.dataToMaster := readData
  io.error := false.B

  writeQueue.io.enq.valid := false.B
  writeQueue.io.enq.bits := 0.U.asTypeOf(new TimerWriteReq)

  readReqQueue.io.enq.valid := false.B
  readReqQueue.io.enq.bits := 0.U.asTypeOf(new TimerReadReq)

  readDataQueue.io.deq.ready := false.B

  switch(state) {
    is(sIdle) {
      when(io.cycleActive && io.strobe) {
        when(io.writeEnable) {
          // Write request
          writeQueue.io.enq.valid := true.B
          writeQueue.io.enq.bits.addr := regAddr
          writeQueue.io.enq.bits.data := Cat(
            Mux(isHighAccess, io.dataToSlave, 0.U),
            Mux(isLowAccess, io.dataToSlave, 0.U)
          )
          writeQueue.io.enq.bits.isLow := isLowAccess
          writeQueue.io.enq.bits.isHigh := isHighAccess

          when(writeQueue.io.enq.ready) {
            io.ack := true.B
            state := sIdle // Immediate ack for writes
          }.otherwise {
            // Save request for retry
            savedRegAddr := regAddr
            savedIsLow := isLowAccess
            savedIsHigh := isHighAccess
            savedWriteData := Cat(
              Mux(isHighAccess, io.dataToSlave, 0.U),
              Mux(isLowAccess, io.dataToSlave, 0.U)
            )
            state := sWrite // Stall if queue full
          }
        }.otherwise {
          // Read request
          readReqQueue.io.enq.valid := true.B
          readReqQueue.io.enq.bits.addr := regAddr

          when(readReqQueue.io.enq.ready) {
            savedIsLow := isLowAccess
            savedIsHigh := isHighAccess
            state := sRead
          }.otherwise {
            io.stall := true.B // Queue full, stall
          }
        }
      }
    }

    is(sWrite) {
      // Retry write
      writeQueue.io.enq.valid := true.B
      writeQueue.io.enq.bits.addr := savedRegAddr
      writeQueue.io.enq.bits.data := savedWriteData
      writeQueue.io.enq.bits.isLow := savedIsLow
      writeQueue.io.enq.bits.isHigh := savedIsHigh

      when(writeQueue.io.enq.ready) {
        io.ack := true.B
        state := sIdle
      }
    }

    is(sRead) {
      // Wait for read data
      readDataQueue.io.deq.ready := true.B
      when(readDataQueue.io.deq.valid) {
        val data64 = readDataQueue.io.deq.bits
        readData := Mux(savedIsLow, data64(31, 0), data64(63, 32))
        io.ack := true.B
        io.dataToMaster := Mux(savedIsLow, data64(31, 0), data64(63, 32))
        state := sIdle
      }
    }
  }
}
