package svarog.bits

import chisel3._
import chisel3.util._
import svarog.memory.DataCacheIO

/** Memory-mapped UART with single-byte TX/RX buffers. */
class UART(xlen: Int, clockHz: Int, baud: Int) extends Module {
  require(clockHz > baud, "UART requires clock frequency greater than baud rate")

  private val cyclesPerBit = (clockHz / baud) max 1
  private val baudCntWidth = math.max(1, log2Ceil(cyclesPerBit))
  private val halfPeriod = (cyclesPerBit / 2) max 1

  val io = IO(new Bundle {
    val bus = Flipped(new DataCacheIO(xlen))
    val tx = Output(Bool())
    val rx = Input(Bool())
  })

  // ----- Transmit path -----
  val txBusy = RegInit(false.B)
  val txBaudCnt = RegInit(0.U(baudCntWidth.W))
  val txBitIdx = RegInit(0.U(4.W))
  val txShift = RegInit("b1111111111".U(10.W))

  // ----- Request/response defaults -----
  val addrWord = io.bus.req.bits.addr(3, 2)
  val reqWrite = io.bus.req.bits.write

  // Registers: 0x0 TXDATA (write), 0x4 STATUS (read), 0x8 RXDATA (read)
  val selTxData = addrWord === "b00".U
  val selStatus = addrWord === "b01".U
  val selRxData = addrWord === "b10".U

  // Ready only de-asserted if trying to write TX while busy
  io.bus.req.ready := !reqWrite || (selTxData && !txBusy)

  val respValidReg = RegInit(false.B)
  val respDataReg = RegInit(0.U(xlen.W))
  io.bus.resp.valid := respValidReg
  io.bus.resp.bits.data := respDataReg
  when(io.bus.resp.fire) {
    respValidReg := false.B
  }

  when(io.bus.req.fire && reqWrite && selTxData) {
    txShift := Cat(1.U(1.W), io.bus.req.bits.data(7, 0), 0.U(1.W))
    txBusy := true.B
    txBaudCnt := 0.U
    txBitIdx := 0.U
  }

  io.tx := Mux(txBusy, txShift(0), true.B)
  when(txBusy) {
    val terminalCount = (cyclesPerBit - 1).U
    when(txBaudCnt === terminalCount) {
      txBaudCnt := 0.U
      txShift := Cat(1.U(1.W), txShift(9, 1))
      txBitIdx := txBitIdx + 1.U
      when(txBitIdx === 9.U) {
        txBusy := false.B
        txShift := "b1111111111".U
      }
    }.otherwise {
      txBaudCnt := txBaudCnt + 1.U
    }
  }

  // ----- Receive path -----
  val rxSync = RegNext(RegNext(io.rx, init = true.B), init = true.B)
  val lastRx = RegNext(rxSync, init = true.B)
  val rxBusy = RegInit(false.B)
  val rxBaudCnt = RegInit(0.U(baudCntWidth.W))
  val rxBitIdx = RegInit(0.U(4.W))
  val rxData = RegInit(0.U(8.W))
  val rxValid = RegInit(false.B)
  val rxShift = RegInit(0.U(8.W))

  when(!rxBusy) {
    when(lastRx && !rxSync) {
      // Detected start bit
      rxBusy := true.B
      rxBaudCnt := (halfPeriod - 1).U
      rxBitIdx := 0.U
      rxShift := 0.U
    }
  }.otherwise {
    when(rxBaudCnt === 0.U) {
      rxBaudCnt := (cyclesPerBit - 1).U
      when(rxBitIdx < 8.U) {
        val bitMask = (1.U(8.W) << rxBitIdx).asUInt
        val cleared = rxShift & ~bitMask
        val newBits = Mux(rxSync, bitMask, 0.U(8.W))
        rxShift := cleared | newBits
        rxBitIdx := rxBitIdx + 1.U
      }.otherwise {
        // Stop bit sampled, capture byte
        rxData := rxShift
        rxValid := true.B
        rxBusy := false.B
      }
    }.otherwise {
      rxBaudCnt := rxBaudCnt - 1.U
    }
  }

  // Clear RX valid when CPU reads RXDATA
  val readRx = io.bus.req.fire && !reqWrite && selRxData
  when(readRx) {
    rxValid := false.B
  }

  // ----- Read responses -----
  when(io.bus.req.fire && !reqWrite) {
    respValidReg := true.B
    val statusBits = Cat(
      0.U((xlen - 2).W),
      rxValid,
      !txBusy
    )
    respDataReg := MuxCase(0.U, Seq(
      selStatus -> statusBits,
      selRxData -> Cat(0.U((xlen - 8).W), rxData)
    ))
  }
}
