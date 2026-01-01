package svarog.memory

import chisel3._
import chisel3.util._
import svarog.bits.asLE

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
    val sIdle, sRespWait, sHostWait, sCooldown = Value
  }
}

class MemWishboneHost(xlen: Int, maxReqWidth: Int, registered: Boolean = false)
    extends Module
    with WishboneMaster {
  import MemWishboneHost.State._

  val mem = IO(Flipped(new MemoryIO(xlen, maxReqWidth)))

  val io = IO(new WishboneIO(xlen, maxReqWidth))

  private val wordBytes = xlen / 8

  private val state = RegInit(sIdle)

  private val savedResp = RegInit(0.U.asTypeOf(new MemoryResponse(maxReqWidth)))
  private val savedReq = RegInit(
    0.U.asTypeOf(new MemoryRequest(xlen, maxReqWidth))
  )

  val stateTest = IO(Output(MemWishboneHost.State.Type()))
  stateTest := state

  mem.req.ready := state === sIdle

  mem.resp.valid := false.B
  mem.resp.bits := 0.U.asTypeOf(new MemoryResponse(maxReqWidth))

  io.cycleActive := false.B
  io.strobe := false.B
  io.writeEnable := false.B
  io.addr := 0.U
  io.dataToSlave := 0.U
  io.sel := VecInit(Seq.fill(wordBytes)(false.B))

  private def saveResp(resp: MemoryResponse) = {
    resp.dataRead := asLE(io.dataToMaster)
    resp.valid := !io.error
  }

  switch(state) {
    is(sIdle) {
      when(mem.req.valid) {
        state := sRespWait

        // Latch the request for the entire transaction
        savedReq := mem.req.bits

        io.cycleActive := true.B
        io.strobe := true.B
        io.writeEnable := mem.req.bits.write
        io.addr := mem.req.bits.address
        io.dataToSlave := Cat(mem.req.bits.dataWrite)
        io.sel := mem.req.bits.mask.reverse
      }
    }
    is(sRespWait) {
      io.cycleActive := true.B
      io.strobe := true.B
      io.writeEnable := savedReq.write
      io.addr := savedReq.address
      io.dataToSlave := Cat(savedReq.dataWrite)
      io.sel := savedReq.mask.reverse
      when(io.ack) {
        when(mem.resp.ready) {
          if (registered) {
            // We're back to idle on next cycle
            state := sIdle
          } else {
            // Let other masters do their job
            state := sCooldown
          }

          mem.resp.valid := true.B
          saveResp(mem.resp.bits)
        }.otherwise {
          state := sHostWait
          saveResp(savedResp)
        }
      }
    }
    is(sHostWait) {
      io.cycleActive := true.B
      io.strobe := true.B
      io.writeEnable := savedReq.write
      io.addr := savedReq.address
      io.dataToSlave := Cat(savedReq.dataWrite)
      io.sel := savedReq.mask.reverse

      when(mem.resp.ready) {
        if (registered) {
          state := sIdle
        } else {
          state := sCooldown
        }
        mem.resp.valid := true.B
        mem.resp.bits := savedResp
      }
    }

    is(sCooldown) {
      state := sIdle
    }
  }
}
