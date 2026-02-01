package svarog.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.{Success, Failure}
import io.circe.yaml.parser._
import svarog.config.{SoCYaml, SoC}

class ConfigSpec extends AnyFlatSpec with Matchers {

  behavior of "ISA.parseFromString"

  it should "parse valid RV32I ISA string" in {
    val result = ISA.parseFromString("rv32i")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 32,
      mult = false,
      zmmul = false,
      zicsr = false,
      zicntr = false,
    )
  }

  it should "parse valid RV64I ISA string" in {
    val result = ISA.parseFromString("rv64i")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 64,
      mult = false,
      zmmul = false,
      zicsr = false,
      zicntr = false,
    )
  }

  it should "parse RV32IM with M extension" in {
    val result = ISA.parseFromString("rv32im")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 32,
      mult = true,
      zmmul = true, // M extension implies zmmul
      zicsr = false,
      zicntr = false,
    )
  }

  it should "parse RV32I with Zicsr extension" in {
    val result = ISA.parseFromString("rv32i_zicsr")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 32,
      mult = false,
      zmmul = false,
      zicsr = true,
      zicntr = false,
    )
  }

  it should "parse RV32I with Zmmul extension" in {
    val result = ISA.parseFromString("rv32i_zmmul")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 32,
      mult = false,
      zmmul = true,
      zicsr = false,
      zicntr = false,
    )
  }

  it should "parse RV32IM with multiple Z extensions" in {
    val result = ISA.parseFromString("rv32im_zicsr_zmmul")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 32,
      mult = true,
      zmmul = true,
      zicsr = true,
      zicntr = false,
    )
  }

  it should "parse RV64IM_Zicsr" in {
    val result = ISA.parseFromString("rv64im_zicsr")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 64,
      mult = true,
      zmmul = true, // M extension implies zmmul
      zicsr = true,
      zicntr = false,
    )
  }

  it should "be case insensitive" in {
    val result = ISA.parseFromString("RV32IM_ZICSR")
    result shouldBe a[Success[_]]
    result.get shouldBe ISA(
      xlen = 32,
      mult = true,
      zmmul = true, // M extension implies zmmul
      zicsr = true,
      zicntr = false,
    )
  }

  it should "reject ISA string without base 'i' extension" in {
    val result = ISA.parseFromString("rv32m")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include(
      "must include base 'i' extension"
    )
  }

  it should "reject unsupported xlen values" in {
    val result = ISA.parseFromString("rv128i")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include("invalid xlen")
  }

  it should "reject invalid xlen (non-numeric)" in {
    val result = ISA.parseFromString("rvXXi")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include(
      "invalid RISC-V extension string"
    )
  }

  it should "reject unsupported base extensions" in {
    val result = ISA.parseFromString("rv32imafd")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include("unsupported extensions")
  }

  it should "reject unsupported Z extensions" in {
    val result = ISA.parseFromString("rv32i_zifencei")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include("unsupported extensions")
  }

  it should "reject completely invalid format" in {
    val result = ISA.parseFromString("invalid")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include(
      "invalid RISC-V extension string"
    )
  }

  it should "reject empty string" in {
    val result = ISA.parseFromString("")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include(
      "invalid RISC-V extension string"
    )
  }

  it should "reject string without 'rv' prefix" in {
    val result = ISA.parseFromString("32im")
    result shouldBe a[Failure[_]]
    result.failed.get.getMessage should include(
      "invalid RISC-V extension string"
    )
  }

  behavior of "CoreType decoder"

  it should "decode 'micro' to Micro" in {
    val yaml = "micro"
    val result = parse(yaml).flatMap(_.as[CoreType](Config.coreTypeDecoder))
    result shouldBe Right(Micro)
  }

  it should "decode 'mini' to Mini" in {
    val yaml = "mini"
    val result = parse(yaml).flatMap(_.as[CoreType](Config.coreTypeDecoder))
    result shouldBe Right(Mini)
  }

  it should "reject invalid core type" in {
    val yaml = "invalid"
    val result = parse(yaml).flatMap(_.as[CoreType](Config.coreTypeDecoder))
    result shouldBe a[Left[_, _]]
  }

  behavior of "ISA decoder"

  it should "decode ISA string using parseFromString" in {
    val yaml = "rv32im_zicsr"
    val result = parse(yaml).flatMap(_.as[ISA](Config.isaDecoder))
    result shouldBe Right(
      ISA(
        xlen = 32,
        mult = true,
        zmmul = true, // M extension implies zmmul
        zicsr = true,
        zicntr = false,
      )
    )
  }

  it should "reject invalid ISA string" in {
    val yaml = "invalid"
    val result = parse(yaml).flatMap(_.as[ISA](Config.isaDecoder))
    result shouldBe a[Left[_, _]]
  }

  behavior of "Cluster decoder"

  it should "decode valid cluster YAML" in {
    val yaml = """coreType: micro
isa: rv32im_zicsr
numCores: 4
"""
    val result = parse(yaml).flatMap(_.as[Cluster](Config.clusterDecoder))
    result shouldBe Right(
      Cluster(
        coreType = Micro,
        isa = ISA(
          32,
          mult = true,
          zmmul = true,
          zicsr = true,
          zicntr = false,
        ), // M extension implies zmmul
        numCores = 4
      )
    )
  }

  it should "reject cluster with invalid ISA" in {
    val yaml = """coreType: micro
isa: rv32xyz
numCores: 4
"""
    val result = parse(yaml).flatMap(_.as[Cluster](Config.clusterDecoder))
    result shouldBe a[Left[_, _]]
  }

  it should "reject cluster with invalid core type" in {
    val yaml = """coreType: invalid
isa: rv32i
numCores: 4
"""
    val result = parse(yaml).flatMap(_.as[Cluster](Config.clusterDecoder))
    result shouldBe a[Left[_, _]]
  }

  behavior of "SoCYaml decoder"

  it should "decode valid SoC YAML with single cluster" in {
    val yaml = """clusters:
  - coreType: micro
    isa: rv32im
    numCores: 2
io: []
memories: []
"""
    val result = parse(yaml).flatMap(_.as[SoCYaml](Config.socYamlDecoder))
    result shouldBe Right(
      SoCYaml(
        clusters = Seq(
          Cluster(
            coreType = Micro,
            isa = ISA(32, mult = true, zmmul = true, zicsr = false, zicntr = false),
            numCores = 2
          )
        ),
        io = Seq(),
        memories = Seq()
      )
    )
  }

  it should "decode valid SoC YAML with multiple clusters" in {
    val yaml = """clusters:
  - coreType: micro
    isa: rv32im_zicsr
    numCores: 4
  - coreType: mini
    isa: rv64i
    numCores: 1
io: []
memories: []
"""
    val result = parse(yaml).flatMap(_.as[SoCYaml](Config.socYamlDecoder))
    result shouldBe a[Right[_, _]]
    val soc = result.getOrElse(fail("Failed to decode SoC"))
    soc.clusters should have length 2
    soc.io should have length 0
    soc.memories should have length 0
  }

  it should "reject SoC with invalid cluster" in {
    val yaml = """clusters:
  - coreType: micro
    isa: invalid_isa
    numCores: 2
io: []
memories: []
"""
    val result = parse(yaml).flatMap(_.as[SoCYaml](Config.socYamlDecoder))
    result shouldBe a[Left[_, _]]
  }

  behavior of "IO decoder"

  it should "decode UART with type discriminator" in {
    val yaml = """type: uart
name: console
baseAddr: 268435456
"""
    val result = parse(yaml).flatMap(_.as[IO](Config.ioDecoder))
    result shouldBe Right(
      UART(
        name = "console",
        baseAddr = 268435456L
      )
    )
  }

  it should "decode UART with hex baseAddr" in {
    val yaml = """type: uart
name: debug
baseAddr: 0x10000000
"""
    val result = parse(yaml).flatMap(_.as[IO](Config.ioDecoder))
    result shouldBe Right(
      UART(
        name = "debug",
        baseAddr = 0x10000000L
      )
    )
  }

  it should "reject IO with unknown type" in {
    val yaml = """type: SPI
name: spi0
baseAddr: 0x20000000
"""
    val result = parse(yaml).flatMap(_.as[IO](Config.ioDecoder))
    result shouldBe a[Left[_, _]]
    result.left.get.getMessage should include("unknown IO type: SPI")
  }

  it should "reject IO without type field" in {
    val yaml = """name: console
baseAddr: 0x10000000
"""
    val result = parse(yaml).flatMap(_.as[IO](Config.ioDecoder))
    result shouldBe a[Left[_, _]]
  }

  behavior of "Memory decoder"

  it should "decode TCM with type discriminator" in {
    val yaml = """type: tcm
baseAddress: 2147483648
length: 65536
"""
    val result = parse(yaml).flatMap(_.as[Memory](Config.memoryDecoder))
    result shouldBe Right(
      TCM(
        baseAddress = 2147483648L,
        length = 65536L
      )
    )
  }

  it should "decode TCM with hex addresses" in {
    val yaml = """type: tcm
baseAddress: 0x80000000
length: 0x10000
"""
    val result = parse(yaml).flatMap(_.as[Memory](Config.memoryDecoder))
    result shouldBe Right(
      TCM(
        baseAddress = 0x80000000L,
        length = 0x10000L
      )
    )
  }

  it should "reject Memory with unknown type" in {
    val yaml = """type: DRAM
baseAddress: 0x80000000
length: 0x10000000
"""
    val result = parse(yaml).flatMap(_.as[Memory](Config.memoryDecoder))
    result shouldBe a[Left[_, _]]
    result.left.get.getMessage should include("unknown Memory type: DRAM")
  }

  it should "reject Memory without type field" in {
    val yaml = """baseAddress: 0x80000000
length: 0x10000
"""
    val result = parse(yaml).flatMap(_.as[Memory](Config.memoryDecoder))
    result shouldBe a[Left[_, _]]
  }

  behavior of "SoCYaml decoder with IO and Memory"

  it should "decode complete SoC YAML with IO and Memory" in {
    val yaml = """clusters:
  - coreType: micro
    isa: rv32im_zicsr
    numCores: 4
io:
  - type: uart
    name: console
    baseAddr: 0x10000000
  - type: uart
    name: debug
    baseAddr: 0x10001000
memories:
  - type: tcm
    baseAddress: 0x80000000
    length: 0x10000
  - type: tcm
    baseAddress: 0x90000000
    length: 0x20000
"""
    val result = parse(yaml).flatMap(_.as[SoCYaml](Config.socYamlDecoder))
    result shouldBe a[Right[_, _]]
    val soc = result.getOrElse(fail("Failed to decode SoC"))
    soc.clusters should have length 1
    soc.io should have length 2
    soc.memories should have length 2

    soc.io(0) shouldBe UART("console", 0x10000000L)
    soc.io(1) shouldBe UART("debug", 0x10001000L)
    soc.memories(0) shouldBe TCM(0x80000000L, 0x10000L)
    soc.memories(1) shouldBe TCM(0x90000000L, 0x20000L)
  }

  it should "reject SoC with invalid IO type" in {
    val yaml = """clusters:
  - coreType: micro
    isa: rv32i
    numCores: 1
io:
  - type: InvalidIO
    name: bad
    baseAddr: 0x10000000
memories: []
"""
    val result = parse(yaml).flatMap(_.as[SoCYaml](Config.socYamlDecoder))
    result shouldBe a[Left[_, _]]
  }

  it should "reject SoC with invalid Memory type" in {
    val yaml = """clusters:
  - coreType: micro
    isa: rv32i
    numCores: 1
io: []
memories:
  - type: InvalidMemory
    baseAddress: 0x80000000
    length: 0x10000
"""
    val result = parse(yaml).flatMap(_.as[SoCYaml](Config.socYamlDecoder))
    result shouldBe a[Left[_, _]]
  }
}
