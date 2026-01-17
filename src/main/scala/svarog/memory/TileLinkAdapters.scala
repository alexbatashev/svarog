package svarog.memory

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.{
  AddressSet,
  IdRange,
  RegionType,
  TransferSizes
}
import freechips.rocketchip.tilelink._

final class ROMTileLinkAdapter(
    xlen: Int,
    baseAddr: Long = 0,
    file: String
)(implicit p: Parameters)
    extends LazyModule {

  private val beatBytes = xlen / 8
  private val romSizeBytes = 65536L

  val node = TLManagerNode(
    Seq(
      TLSlavePortParameters.v1(
        Seq(
          TLSlaveParameters.v1(
            address = Seq(AddressSet(baseAddr, romSizeBytes - 1)),
            regionType = RegionType.UNCACHED,
            executable = true,
            supportsGet = TransferSizes(1, beatBytes),
            // Declare Put support for TileLink negotiation, but writes are denied
            supportsPutFull = TransferSizes(1, beatBytes),
            supportsPutPartial = TransferSizes(1, beatBytes),
            fifoId = Some(0)
          )
        ),
        beatBytes = beatBytes,
        minLatency = 1
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    private val mem = Module(new ROM(xlen, baseAddr, file))
    private val memPort = mem.io
    private val (in, edge) = node.in(0)

    private object State extends ChiselEnum {
      val sIdle, sMemWait, sResp = Value
    }

    private val state = RegInit(State.sIdle)
    private val savedSource = Reg(UInt(edge.bundle.sourceBits.W))
    private val savedSize = Reg(UInt(edge.bundle.sizeBits.W))
    private val savedIsGet = RegInit(false.B)
    private val pendingDenied = RegInit(false.B)
    private val pendingData = RegInit(0.U(xlen.W))

    val isGet = in.a.bits.opcode === TLMessages.Get
    val isPut = in.a.bits.opcode === TLMessages.PutFullData ||
      in.a.bits.opcode === TLMessages.PutPartialData

    val useMem = isGet

    in.a.ready := state === State.sIdle && (memPort.req.ready || !useMem)

    memPort.req.valid := in.a.fire && useMem
    memPort.req.bits.address := in.a.bits.address
    memPort.req.bits.write := false.B
    memPort.req.bits.mask := VecInit(in.a.bits.mask.asBools)
    memPort.req.bits.dataWrite := 0.U.asTypeOf(memPort.req.bits.dataWrite)

    memPort.resp.ready := state === State.sMemWait

    in.d.valid := state === State.sResp
    in.d.bits := Mux(
      savedIsGet,
      edge.AccessAck(
        savedSource,
        savedSize,
        pendingData,
        denied = pendingDenied,
        corrupt = pendingDenied
      ),
      edge.AccessAck(savedSource, savedSize, denied = pendingDenied)
    )

    in.b.valid := false.B
    in.c.ready := true.B
    in.e.ready := true.B

    switch(state) {
      is(State.sIdle) {
        when(in.a.fire) {
          savedSource := in.a.bits.source
          savedSize := in.a.bits.size
          savedIsGet := isGet

          when(isPut) {
            pendingDenied := true.B
            pendingData := 0.U
            state := State.sResp
          }.otherwise {
            state := State.sMemWait
          }
        }
      }

      is(State.sMemWait) {
        when(memPort.resp.valid) {
          pendingDenied := !memPort.resp.bits.valid
          pendingData := memPort.resp.bits.dataRead.asUInt
          state := State.sResp
        }
      }

      is(State.sResp) {
        when(in.d.fire) {
          state := State.sIdle
        }
      }
    }
  }
}

final class MemoryIOTileLinkAdapter(
    name: String,
    xlen: Int,
    sourceId: IdRange
)(implicit p: Parameters)
    extends LazyModule {

  private val beatBytes = xlen / 8

  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = sourceId,
            supportsProbe = TransferSizes(1, beatBytes),
            supportsGet = TransferSizes(1, beatBytes),
            supportsPutFull = TransferSizes(1, beatBytes),
            supportsPutPartial = TransferSizes(1, beatBytes)
          )
        )
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val mem = IO(Flipped(new MemoryIO(xlen, xlen)))

    private val (out, edge) = node.out(0)

    private object State extends ChiselEnum {
      val sIdle, sAWait, sDWait = Value
    }

    private val state = RegInit(State.sIdle)
    private val savedReq = Reg(new MemoryRequest(xlen, xlen))
    private val savedIsWrite = RegInit(false.B)

    private def maskToSize(mask: Vec[Bool]): UInt = {
      val count = PopCount(mask)
      MuxLookup(count, 0.U(3.W))(
        Seq(
          1.U -> 0.U,
          2.U -> 1.U,
          4.U -> 2.U,
          8.U -> 3.U
        )
      )
    }

    mem.req.ready := state === State.sIdle
    mem.resp.valid := out.d.valid && state === State.sDWait
    mem.resp.bits.valid := !out.d.bits.denied && !out.d.bits.corrupt
    mem.resp.bits.dataRead := svarog.bits.asLE(out.d.bits.data)

    out.d.ready := mem.resp.ready && state === State.sDWait

    out.a.valid := state === State.sAWait
    out.a.bits := {
      val size = maskToSize(savedReq.mask)
      val data = savedReq.dataWrite.asUInt
      val mask = savedReq.mask.asUInt
      val sourceId = edge.client.masters.head.sourceId.start.U
      val (_, getA) = edge.Get(sourceId, savedReq.address, size)
      val (_, putA) = edge.Put(sourceId, savedReq.address, size, data, mask)
      val a = Wire(new TLBundleA(edge.bundle))
      a := getA
      when(savedIsWrite) {
        a := putA
      }
      a
    }

    switch(state) {
      is(State.sIdle) {
        when(mem.req.fire) {
          savedReq := mem.req.bits
          savedIsWrite := mem.req.bits.write
          state := State.sAWait
        }
      }

      is(State.sAWait) {
        when(out.a.fire) {
          state := State.sDWait
        }
      }

      is(State.sDWait) {
        when(out.d.fire) {
          state := State.sIdle
        }
      }
    }
  }
}

final class MemoryIOTileLinkBundleAdapter(
    edge: TLEdgeOut,
    xlen: Int
) extends Module {
  val mem = IO(Flipped(new MemoryIO(xlen, xlen)))
  val tl = IO(new TLBundle(edge.bundle))

  private object State extends ChiselEnum {
    val sIdle, sAWait, sDWait = Value
  }

  private val state = RegInit(State.sIdle)
  private val savedReq = Reg(new MemoryRequest(xlen, xlen))
  private val savedIsWrite = RegInit(false.B)

  private val wordSize = xlen / 8

  // Convert mask count to TileLink size (log2 of bytes)
  private def maskToSize(mask: Vec[Bool]): UInt = {
    val count = PopCount(mask)
    MuxLookup(count, 0.U(3.W))(
      Seq(
        1.U -> 0.U,
        2.U -> 1.U,
        4.U -> 2.U,
        8.U -> 3.U
      )
    )
  }

  // Find the byte offset (first set bit in mask)
  private def maskToOffset(mask: Vec[Bool]): UInt = {
    PriorityEncoder(mask.asUInt)
  }

  mem.req.ready := state === State.sIdle
  mem.resp.valid := tl.d.valid && state === State.sDWait
  mem.resp.bits.valid := !tl.d.bits.denied && !tl.d.bits.corrupt
  mem.resp.bits.dataRead := svarog.bits.asLE(tl.d.bits.data)

  tl.d.ready := mem.resp.ready && state === State.sDWait

  tl.a.valid := state === State.sAWait
  tl.a.bits := {
    val size = maskToSize(savedReq.mask)
    val offset = maskToOffset(savedReq.mask)
    val sourceId = edge.client.masters.head.sourceId.start.U
    // Compute actual byte address from word-aligned address + offset
    val byteAddr = savedReq.address + offset
    // savedReq.dataWrite/mask are already aligned to word lanes
    val data = savedReq.dataWrite.asUInt
    val mask = savedReq.mask.asUInt
    val (_, getA) = edge.Get(sourceId, byteAddr, size)
    val (_, putA) = edge.Put(sourceId, byteAddr, size, data, mask)
    val a = Wire(new TLBundleA(edge.bundle))
    a := getA
    when(savedIsWrite) {
      a := putA
    }
      a
    }

  // Debug: log store requests to help track unmapped addresses
  when(tl.a.valid && tl.a.bits.opcode === TLMessages.PutPartialData) {
    printf(
      p"[MemoryTL] PutPartial addr=0x${Hexadecimal(tl.a.bits.address)} size=${tl.a.bits.size} mask=0x${Hexadecimal(tl.a.bits.mask)} data=0x${Hexadecimal(tl.a.bits.data)}\n"
    )
  }

  tl.b.valid := false.B
  tl.c.ready := true.B
  tl.e.ready := true.B

  switch(state) {
    is(State.sIdle) {
      when(mem.req.fire) {
        savedReq := mem.req.bits
        savedIsWrite := mem.req.bits.write
        state := State.sAWait
      }
    }

    is(State.sAWait) {
      when(tl.a.fire) {
        state := State.sDWait
      }
    }

    is(State.sDWait) {
      when(tl.d.fire) {
        state := State.sIdle
      }
    }
  }
}
