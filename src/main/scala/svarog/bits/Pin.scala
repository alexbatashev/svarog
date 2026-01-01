package svarog.bits

import chisel3._

/** This is a temporary workaround for malfunction analog pins in Chisel
  */
class Pin extends Bundle {
  val write = Output(Bool())
  val output = Output(Bool())
  val input = Input(Bool())
}
