package svarog.bits

import chisel3._
import chisel3.util._
import svarog.memory.{WishboneIO, WishboneSlave}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy.{AddressSet, IdRange, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._

class UartTx(val dataWidth: Int = 8) extends Module {
  val io = IO(new Bundle {
    val txd = Output(Bool())
    val channel = Flipped(Decoupled(UInt(dataWidth.W)))
    val baudDivider = Input(UInt(16.W))
  })

  object State extends ChiselEnum {
    val sIdle, sStart, sData, sStop = Value
  }

  val state = RegInit(State.sIdle)
  val shiftReg = RegInit(0.U(dataWidth.W))
  val bitCounter = RegInit(0.U(log2Ceil(dataWidth + 1).W))
  val baudCounter = RegInit(0.U(16.W))

  io.txd := true.B
  io.channel.ready := false.B

  switch(state) {
    is(State.sIdle) {
      io.txd := true.B
      io.channel.ready := true.B
      when(io.channel.valid) {
        shiftReg := io.channel.bits
        bitCounter := 0.U
        baudCounter := 0.U
        state := State.sStart
      }
    }

    is(State.sStart) {
      io.txd := false.B
      when(baudCounter === io.baudDivider) {
        baudCounter := 0.U
        state := State.sData
      }.otherwise {
        baudCounter := baudCounter + 1.U
      }
    }

    is(State.sData) {
      io.txd := shiftReg(0)
      when(baudCounter === io.baudDivider) {
        baudCounter := 0.U
        shiftReg := shiftReg >> 1
        bitCounter := bitCounter + 1.U
        when(bitCounter === (dataWidth - 1).U) {
          state := State.sStop
        }
      }.otherwise {
        baudCounter := baudCounter + 1.U
      }
    }

    is(State.sStop) {
      io.txd := true.B
      when(baudCounter === io.baudDivider) {
        baudCounter := 0.U
        state := State.sIdle
      }.otherwise {
        baudCounter := baudCounter + 1.U
      }
    }
  }
}

class UartRx(val dataWidth: Int = 8) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val channel = Decoupled(UInt(dataWidth.W))
    val baudDivider = Input(UInt(16.W))
  })

  object State extends ChiselEnum {
    val sIdle, sStart, sData, sStop = Value
  }

  val state = RegInit(State.sIdle)
  val shiftReg = RegInit(0.U(dataWidth.W))
  val bitCounter = RegInit(0.U(log2Ceil(dataWidth + 1).W))
  val baudCounter = RegInit(0.U(16.W))
  val rxdSync = RegNext(RegNext(io.rxd, true.B), true.B)
  val receivedData = RegInit(0.U(dataWidth.W))
  val dataValid = RegInit(false.B)

  io.channel.bits := receivedData
  io.channel.valid := dataValid

  when(io.channel.fire) {
    dataValid := false.B
  }

  switch(state) {
    is(State.sIdle) {
      when(!rxdSync) {
        baudCounter := io.baudDivider >> 1
        state := State.sStart
      }
    }

    is(State.sStart) {
      when(baudCounter === io.baudDivider) {
        baudCounter := 0.U
        bitCounter := 0.U
        when(!rxdSync) {
          state := State.sData
        }.otherwise {
          state := State.sIdle
        }
      }.otherwise {
        baudCounter := baudCounter + 1.U
      }
    }

    is(State.sData) {
      when(baudCounter === io.baudDivider) {
        baudCounter := 0.U
        shiftReg := Cat(rxdSync, shiftReg(dataWidth - 1, 1))
        bitCounter := bitCounter + 1.U
        when(bitCounter === (dataWidth - 1).U) {
          state := State.sStop
        }
      }.otherwise {
        baudCounter := baudCounter + 1.U
      }
    }

    is(State.sStop) {
      when(baudCounter === io.baudDivider) {
        baudCounter := 0.U
        state := State.sIdle
        when(rxdSync) {
          receivedData := shiftReg
          dataValid := true.B
        }
      }.otherwise {
        baudCounter := baudCounter + 1.U
      }
    }
  }
}

class UartIO(val dataWidth: Int = 8) extends Bundle {
  val txd = Output(Bool())
  val rxd = Input(Bool())

  val tx = Flipped(Decoupled(UInt(dataWidth.W)))
  val rx = Decoupled(UInt(dataWidth.W))

  val baudDivider = Input(UInt(16.W))
}

class Uart(val dataWidth: Int = 8) extends Module {
  val io = IO(new UartIO(dataWidth))

  val tx = Module(new UartTx(dataWidth))
  val rx = Module(new UartRx(dataWidth))

  tx.io.txd <> io.txd
  tx.io.channel <> io.tx
  tx.io.baudDivider := io.baudDivider

  rx.io.rxd <> io.rxd
  rx.io.channel <> io.rx
  rx.io.baudDivider := io.baudDivider
}

object TLUART {
  val DATA_REG_OFFSET = 0x00
  val STATUS_REG_OFFSET = 0x04
  val CONTROL_REG_OFFSET = 0x08
  val BAUD_DIV_OFFSET = 0x0c

  val STATUS_TX_READY = 0
  val STATUS_RX_VALID = 1
}

class TLUART(baseAddr: Long, busWidth: Int = 32, dataWidth: Int = 8)(implicit p: Parameters) extends LazyModule {
  val node = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(
      address = Seq(AddressSet(baseAddr, memSizeBytes - 1)),
      regionType = RegionType.UNCACHED,
      executable = true,
      supportsGet = TransferSizes(1, beatBytes),
      supportsPutFull = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes),
      fifoId = Some(0)
    )),
    beatBytes = 1,
    minLatency = 1
  )))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val uart = IO(new Bundle {
      val txd = Output(Bool())
      val rxd = Input(Bool())
    })
    private val uartCore = Module(new Uart(dataWidth))
    private val (in, edge) = node.in(0)

    val isGet = in.a.bits.opcode === TLMessages.Get
    val isPut = in.a.bits.opcode === TLMessages.PutFullData ||
      in.a.bits.opcode === TLMessages.PutPartialData

    uart.txd := uartCore.io.txd
    uartCore.io.rxd := uart.rxd

    val baudDividerReg = RegInit(434.U(16.W))
    val txDataReg = RegInit(0.U(dataWidth.W))
    val txValidReg = RegInit(false.B)

    uartCore.io.baudDivider := baudDividerReg
    uartCore.io.tx.bits := txDataReg
    uartCore.io.tx.valid := txValidReg
    uartCore.io.rx.ready := false.B

    when(uartCore.io.tx.fire) {
      txValidReg := false.B
    }

    io.ack := false.B
    io.stall := false.B
    io.error := false.B
    io.dataToMaster := 0.U

    val localAddr = io.addr - baseAddr.U

    when(io.cycleActive && io.strobe) {
      io.ack := true.B

      when(io.writeEnable) {
        switch(localAddr) {
          is(DATA_REG_OFFSET.U) {
            txDataReg := io.dataToSlave(dataWidth - 1, 0)
            // Only set valid if not already valid (prevent duplicate transmissions)
            when(!txValidReg) {
              txValidReg := true.B
            }
          }
          is(BAUD_DIV_OFFSET.U) {
            baudDividerReg := io.dataToSlave(15, 0)
          }
        }
      }.otherwise {
        switch(localAddr) {
          is(DATA_REG_OFFSET.U) {
            io.dataToMaster := uartCore.io.rx.bits
            uartCore.io.rx.ready := true.B
          }
          is(STATUS_REG_OFFSET.U) {
            val status = Wire(UInt(busWidth.W))
            status := Cat(
              0.U((busWidth - 2).W),
              uartCore.io.rx.valid,
              uartCore.io.tx.ready
            )
            io.dataToMaster := status
          }
          is(BAUD_DIV_OFFSET.U) {
            io.dataToMaster := baudDividerReg
          }
        }
      }
    }
  }
}
