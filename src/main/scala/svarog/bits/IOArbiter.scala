package svarog.bits

import chisel3._
import chisel3.util._

class ArbiterTag[T <: Data](genType: T, val tagWidth: Int = 4) extends Bundle {
  val tag = UInt(tagWidth.W)
  val data = chisel3.reflect.DataMirror.internal.chiselTypeClone(genType)
}

class IOArbiterPort[I <: Data, O <: Data](
    i: => I,
    o: => O,
    tagWidth: Int = 4
) extends Bundle {
  val in = Flipped(Decoupled(i))
  val out = Decoupled(o)
}

class IOArbiter[I <: Data, O <: Data](
    val i: I,
    val o: O,
    val n: Int
) extends Module {
  require(n > 0, "IOArbiter must have at least one source")
  private val tagWidth = if (n == 1) 1 else log2Ceil(n)

  val io = IO(new Bundle {
    val sources = Vec(n, new IOArbiterPort(i, o, tagWidth))
    val sink = Decoupled(new ArbiterTag(i, tagWidth))
    val resp = Flipped(Decoupled(new ArbiterTag(o, tagWidth)))
  })

  private val sourceArb = Module(new Arbiter(i.cloneType, n))
  private val tagged = Wire(new ArbiterTag(i, tagWidth))

  tagged.data := sourceArb.io.out.bits
  tagged.tag := sourceArb.io.chosen
  io.sink.bits := tagged
  io.sink.valid := sourceArb.io.out.valid
  sourceArb.io.out.ready := io.sink.ready

  // Response ready is true if any source with matching tag is ready
  io.resp.ready := VecInit(io.sources.map(_.out.ready)).asUInt.orR

  for (i <- 0 until n) {
    sourceArb.io.in(i) <> io.sources(i).in

    io.sources(i).out.bits := io.resp.bits.data
    io.sources(i).out.valid := io.resp.valid && (io.resp.bits.tag === i.U)
  }
}
