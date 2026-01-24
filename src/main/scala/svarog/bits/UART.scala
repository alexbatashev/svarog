package svarog.bits

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.resources.SimpleDevice

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
  val DATA_REG = 0x00
  val STATUS_REG = 0x04
  val CONTROL_REG = 0x08
  val BAUD_DIV = 0x0c

  val STATUS_TX_READY = 0
  val STATUS_RX_VALID = 1
}

class TLUART(baseAddr: Long, busWidth: Int = 32, dataWidth: Int = 8)(implicit
    p: Parameters
) extends LazyModule {
  private val beatBytes = busWidth / 8

  val device = new SimpleDevice("uart", Seq("svarog,uart"))
  val node = TLRegisterNode(
    address = Seq(AddressSet(baseAddr, 0xf)),
    device = device,
    beatBytes = beatBytes,
    concurrency = 1
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    import TLUART._

    val uart = IO(new Bundle {
      val txd = Output(Bool())
      val rxd = Input(Bool())
    })
    private val uartCore = Module(new Uart(dataWidth))

    uart.txd := uartCore.io.txd
    uartCore.io.rxd := uart.rxd

    val baudDividerReg = RegInit(434.U(16.W))
    uartCore.io.baudDivider := baudDividerReg

    // TX buffer registers - needed because RegField's Decoupled handling
    // expects to drive valid/bits directly, but we need proper handshaking
    val txDataReg = RegInit(0.U(dataWidth.W))
    val txValidReg = RegInit(false.B)
    val txWriteAck = RegInit(false.B)

    uartCore.io.tx.bits := txDataReg
    uartCore.io.tx.valid := txValidReg

    when(uartCore.io.tx.fire) {
      txValidReg := false.B
    }

    // Status register: bit 0 = TX ready (can accept new data), bit 1 = RX valid
    // TX is ready when not currently waiting to transmit
    val txReady = uartCore.io.tx.ready && !txValidReg
    val status = Cat(0.U(6.W), uartCore.io.rx.valid, txReady)

    node.regmap(
      DATA_REG -> Seq(
        RegField(
          dataWidth,
          uartCore.io.rx,
          RegWriteFn((valid, oready, data) => {
            val canAccept = !txValidReg
            when(txWriteAck && oready) {
              txWriteAck := false.B
            }
            when(valid && canAccept) {
              txDataReg := data
              txValidReg := true.B
              txWriteAck := true.B
            }
            (canAccept, txWriteAck)
          })
        )
      ),
      STATUS_REG -> Seq(
        RegField.r(8, status)
      ),
      BAUD_DIV -> Seq(RegField(16, baudDividerReg))
    )
  }
}
