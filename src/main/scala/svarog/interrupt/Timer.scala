package svarog.interrupt

import chisel3._
import chisel3.util._
import svarog.memory.WishboneSlave
import svarog.memory.WishboneIO

class MaxTimeIO(numHarts: Int) extends Bundle {
  val hartId = Input(UInt(log2Ceil(numHarts).W))
  val value = Input(UInt(64.W))
  val low = Input(Bool())
  val high = Input(Bool())
}

class TimerIO(numHarts: Int) extends Bundle {
  val maxTime = Valid(new MaxTimeIO(numHarts))
  val fire = Output(Vec(numHarts, Bool()))
  val time = Output(UInt(64.W))
}

class Timer(val numHarts: Int) extends Module {
  val io = IO(new Bundle {
    val time = Input(UInt(64.W))
    val control = new TimerIO(numHarts)
  })

  io.control.time := io.time

  for (i <- 0 until numHarts) {
    val timeCmp = RegInit("hffffffffffffffff".U(64.W))

    io.control.fire(i) := io.time >= timeCmp

    when (io.control.maxTime.valid) {
      when(io.control.maxTime.bits.hartId === i.U) {
        when(io.control.maxTime.bits.low) {
          timeCmp(31, 0) := io.control.maxTime.bits.value(31, 0)
        }
        when(io.control.maxTime.bits.high) {
          timeCmp(63, 32) := io.control.maxTime.bits.value(63, 32)
        }
      }
    }
  }
}

class TimerWishbone(numHarts: Int, xlen: Int, maxReqWidth: Int, baseAddr: Long) extends Module with WishboneSlave {
  override val io: WishboneIO = IO(new WishboneIO(xlen, maxReqWidth))

  override def addrStart: Long = baseAddr

  // Layout: mtimecmp[0..numHarts-1] (8 bytes each) + mtime (8 bytes)
  override def addrEnd: Long = baseAddr + numHarts * 8 + 8

  val timer = IO(Flipped(new TimerIO(numHarts)))

  // Default outputs
  io.ack := false.B
  io.stall := false.B
  io.dataToMaster := 0.U
  io.error := false.B

  // Default timer control outputs
  timer.maxTime.valid := false.B
  timer.maxTime.bits.hartId := 0.U
  timer.maxTime.bits.value := 0.U
  timer.maxTime.bits.low := false.B
  timer.maxTime.bits.high := false.B

  // Address decoding
  val offset = io.addr - baseAddr.U
  val mtimeOffset = (numHarts * 8).U

  when(io.cycleActive && io.strobe) {
    io.ack := true.B

    when(offset >= mtimeOffset) {
      // mtime register (read-only)
      val isLow = offset === mtimeOffset
      when(isLow) {
        io.dataToMaster := timer.time(31, 0)
      }.otherwise {
        io.dataToMaster := timer.time(63, 32)
      }
    }.otherwise {
      // mtimecmp registers
      val hartId = offset >> 3
      val isLow = !offset(2)
      val isHigh = offset(2)

      when(io.writeEnable) {
        timer.maxTime.valid := true.B
        timer.maxTime.bits.hartId := hartId
        timer.maxTime.bits.value := io.dataToSlave
        timer.maxTime.bits.low := isLow
        timer.maxTime.bits.high := isHigh
      }
      // Note: mtimecmp reads not supported - Timer doesn't expose stored values
    }
  }
}

object TimerWishbone {
  def apply(timer: Timer, xlen: Int, maxReqWidth: Int, baseAddr: Long): TimerWishbone = {
    val timerWb = Module(new TimerWishbone(timer.numHarts, xlen, maxReqWidth, baseAddr))
    timerWb.timer <> timer.io.control
    timerWb
  }
}
