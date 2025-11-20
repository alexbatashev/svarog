package svarog.bits

import chisel3._
import chisel3.util._

class ArbiterTag[T <: Data](val gen: T, tagWidth: Int = 4) extends Bundle {
  val tag = UInt(tagWidth.W)
  val data = gen.cloneType
}

class IOArbiterPort[I <: Data, O <: Data](
    val i: I,
    val o: O,
    tagWidth: Int = 4
) extends Bundle {
  val in = Flipped(Decoupled(i.cloneType))
  val out = Decoupled(o.cloneType)
}

class IOArbiter[I <: Data, O <: Data](
    val i: I,
    val o: O,
    val n: Int
) extends Module {
  private val tagWidth = log2Ceil(n)

  val io = IO(new Bundle {
    val sources = Vec(n, new IOArbiterPort(i.cloneType, o.cloneType, tagWidth))
    val sink = Decoupled(new ArbiterTag(o.cloneType, tagWidth))
    val resp = Flipped(Decoupled(new ArbiterTag(i.cloneType, tagWidth)))
  })

  private val sourceArb = Module(new Arbiter(i.cloneType, n))
  private val tagged = Wire(new ArbiterTag(i.cloneType, tagWidth))

  tagged.data := sourceArb.io.out.bits
  tagged.tag := sourceArb.io.chosen
  io.sink.bits := tagged
  io.sink.valid := sourceArb.io.out.valid

  for (i <- 0 until n) {
    sourceArb.io.in(i) := io.sources(i).in

    io.sources(i).out.bits := io.resp.bits.data
    io.sources(i).out.valid := io.resp.valid && (io.resp.bits.tag === i.U)
  }
}
