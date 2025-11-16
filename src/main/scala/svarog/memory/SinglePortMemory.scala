package svarog.memory

import chisel3._
import chisel3.util._

class SinglePortMemory(xlen: Int, maxReqWidth: Int)
    extends CpuMemoryInterface(xlen, maxReqWidth) {
  private val arbiter = Module(new Arbiter(new MemoryIO(), ))
}
