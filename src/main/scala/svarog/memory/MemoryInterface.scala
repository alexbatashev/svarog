package svarog.memory

import chisel3._
import chisel3.util._

object MemWidth extends ChiselEnum {
  val BYTE, HALF, WORD, DWORD = Value

  def safe(funct3: UInt): (MemWidth.Type, Bool) = {
    val valid = funct3 <= 3.U
    val width = Mux(valid, funct3(1, 0).asTypeOf(MemWidth()), WORD)
    (width, valid)
  }

  def decode(funct3: UInt): (MemWidth.Type, Bool) = {
    val unsigned = funct3(2)
    val width = funct3(1, 0).asTypeOf(MemWidth())
    (width, !unsigned)
  }

  def size(w: MemWidth.Type): UInt = {
    MuxCase(
      1.U,
      Seq(
        (w === MemWidth.BYTE) -> 1.U,
        (w === MemWidth.HALF) -> 2.U,
        (w === MemWidth.WORD) -> 4.U,
        (w === MemWidth.DWORD) -> 8.U
      )
    )
  }

  def mask(maxReqWidth: Int)(w: MemWidth.Type): Vec[Bool] = {
    val numBytes = maxReqWidth / 8

    val m = Wire(Vec(numBytes, Bool()))

    for (i <- 0 until numBytes) {
      m(i) :=
        ((i < 1).B && w === MemWidth.BYTE) ||
          ((i < 2).B && w === MemWidth.HALF) ||
          ((i < 4).B && w === MemWidth.WORD) ||
          (w === MemWidth.DWORD)
    }

    m
  }

  def apply(value: UInt): MemWidth.Type = value.asTypeOf(MemWidth())
}

// Memory request - only request fields
class MemoryRequest(xlen: Int, maxReqWidth: Int) extends Bundle {
  val address = UInt(xlen.W)
  val dataWrite = Vec(maxReqWidth / 8, UInt(8.W))
  val write = Bool()
  val mask = Vec(maxReqWidth / 8, Bool())
}

// Memory response - only response fields
class MemoryResponse(maxReqWidth: Int) extends Bundle {
  val dataRead = Vec(maxReqWidth / 8, UInt(8.W))
  val valid = Bool()
}

// Full memory interface with separate request/response
class MemoryIO(xlen: Int, maxReqWidth: Int) extends Bundle {
  val req = Decoupled(new MemoryRequest(xlen, maxReqWidth))
  val resp = Flipped(Decoupled(new MemoryResponse(maxReqWidth)))
}

abstract class CpuMemoryInterface(xlen: Int, maxReqWidth: Int) extends Module {
  val io = IO(new Bundle {
    val inst = Flipped(new MemoryIO(xlen, maxReqWidth))
    val data = Flipped(new MemoryIO(xlen, maxReqWidth))
  })
}

object MemWishboneHost {
  object State extends ChiselEnum {
    val sIdle, sRespWait, sHostWait = Value
  }
}

class MemWishboneHost(xlen: Int, maxReqWidth: Int)
    extends Module
    with WishboneMaster {
  import MemWishboneHost.State._

  val mem = IO(Flipped(new MemoryIO(xlen, maxReqWidth)))

  val io = IO(new WishboneIO(xlen, maxReqWidth))

  private val wordBytes = xlen / 8

  private val state = RegInit(sIdle)

  private val savedResp = RegInit(0.U.asTypeOf(new MemoryResponse(maxReqWidth)))

  // By convention, the request must be asserted and valid
  // for the entire duration of transaction. This means we're
  // ready on each cycle.
  mem.req.ready := true.B

  mem.resp.valid := false.B
  mem.resp.bits := 0.U.asTypeOf(new MemoryResponse(maxReqWidth))

  io.cycleActive := false.B
  io.strobe := false.B
  io.writeEnable := false.B
  io.addr := 0.U
  io.dataToSlave := 0.U
  io.sel := VecInit(Seq.fill(wordBytes)(false.B))

  // val log = SimLog.file("/tmp/logfile.log")
  val respValid = mem.resp.valid
  printf(cf"Current state = $state; resp.valid = $respValid\n")
  // log.flush()

  def saveResp(resp: MemoryResponse) = {
    val respData = Wire(Vec(wordBytes, UInt(8.W)))
    for (i <- 0 until wordBytes) {
      respData(i) := io.dataToMaster(i)
    }
    resp.dataRead := respData
    resp.valid := !io.error
  }

  switch(state) {
    is(sIdle) {
      when(mem.req.valid) {
        state := sRespWait

        io.cycleActive := true.B
        io.strobe := true.B
        io.writeEnable := mem.req.bits.write
        io.addr := mem.req.bits.address
        io.dataToSlave := Cat(mem.req.bits.dataWrite)
        io.sel := mem.req.bits.mask
      }
    }
    is(sRespWait) {
      io.cycleActive := true.B
      io.strobe := true.B
      when(io.ack) {
        when(mem.resp.ready) {
          // We're back to idle on next cycle
          state := sIdle

          mem.resp.valid := true.B
          saveResp(mem.resp.bits)
        }.otherwise {
          state := sHostWait
          saveResp(savedResp)
        }
      }
    }
    is(sHostWait) {
      when(mem.resp.ready) {
        state := sIdle
        mem.resp.valid := true.B
        mem.resp.bits := savedResp
      }
    }
  }

  // private val wordBytes = xlen / 8
  // private val offsetWidth = log2Ceil(wordBytes)

  // // Track an outstanding Wishbone transaction
  // val busy = RegInit(false.B)
  // val respPending = RegInit(false.B)
  // val savedReq = RegInit(0.U.asTypeOf(new MemoryRequest(xlen, maxReqWidth)))
  // // Drive Wishbone outputs combinationally from incoming request when ready to accept,
  // // otherwise from saved request
  // val activeReq = Mux(mem.req.fire, mem.req.bits, savedReq)

  // io.cycleActive := busy
  // io.strobe := busy
  // io.writeEnable := activeReq.write
  // io.addr := activeReq.address // word-aligned address
  // io.dataToSlave := Cat(activeReq.dataWrite.reverse)

  // // Pass through the already-shifted mask from Memory stage
  // val selVec = Wire(Vec(wordBytes, Bool()))
  // for (i <- 0 until wordBytes) {
  //   selVec(i) := activeReq.mask(i)
  // }
  // io.sel := selVec

  // // Accept a new memory request only when idle and no pending response
  // val canAccept = !busy && !respPending
  // mem.req.ready := canAccept
  // when(mem.req.fire) {
  //   savedReq := mem.req.bits
  //   busy := true.B
  // }

  // // Detect transaction completion
  // val done = busy && (io.ack || io.error)
  // when(done) {
  //   busy := false.B
  //   respPending := true.B
  // }

  // // Capture return data on ack
  // val wbDataBytesReg = Reg(Vec(wordBytes, UInt(8.W)))
  // when(io.ack) {
  //   for (i <- 0 until wordBytes) {
  //     wbDataBytesReg(i) := io.dataToMaster(8 * (i + 1) - 1, 8 * i)
  //   }
  // }

  // // Return data as-is; Memory stage will unshift based on saved offset
  // val respData = Wire(Vec(wordBytes, UInt(8.W)))
  // for (i <- 0 until wordBytes) {
  //   respData(i) := wbDataBytesReg(i)
  // }

  // // Drive memory response; hold until consumer ready
  // mem.resp.valid := respPending
  // mem.resp.bits.valid := respPending
  // mem.resp.bits.dataRead := respData
  // when(respPending && mem.resp.ready) {
  //   respPending := false.B
  // }
}
