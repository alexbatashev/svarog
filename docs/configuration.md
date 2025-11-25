# Configuration Reference

This document describes configuration options for Svarog SoC.

## SvarogConfig

**Location**: `src/main/scala/svarog/soc/SvarogConfig.scala`

```scala
case class SvarogConfig(
  xlen: Int = 32,
  memSizeBytes: Int = 16384,
  clockHz: Int = 50_000_000,
  uartInitialBaud: Int = 115200,
  programEntryPoint: Long = 0x80000000L,
  enableDebugInterface: Boolean = false
)
```

### Parameters

#### xlen
- **Type**: `Int`
- **Default**: `32`
- **Description**: Data path width in bits
- **Valid values**: `32` (RV32I only)
- **Note**: Fixed to 32 for RV32I implementation

#### memSizeBytes
- **Type**: `Int`
- **Default**: `16384` (16 KB)
- **Description**: Size of tightly-coupled memory (TCM) in bytes
- **Valid values**: Any power of 2, typically 4KB - 1MB
- **Considerations**:
  - Larger = more program space, more FPGA resources
  - FPGA BRAM typically comes in 18Kb or 36Kb blocks
  - 16KB = reasonable for small programs

**Examples**:
```scala
memSizeBytes = 4096        // 4 KB - minimal
memSizeBytes = 65536       // 64 KB - moderate
memSizeBytes = 1024 * 1024 // 1 MB - large (simulation)
```

#### clockHz
- **Type**: `Int`
- **Default**: `50_000_000` (50 MHz)
- **Description**: Target clock frequency in Hz
- **Valid values**: Platform-dependent
- **Uses**: UART baud rate calculation, timing peripherals
- **Note**: Does not constrain actual synthesis frequency

**Examples**:
```scala
clockHz = 25_000_000   // 25 MHz - conservative
clockHz = 50_000_000   // 50 MHz - typical
clockHz = 100_000_000  // 100 MHz - aggressive
```

#### uartInitialBaud
- **Type**: `Int`
- **Default**: `115200`
- **Description**: UART baud rate in bits per second
- **Valid values**: Standard baud rates (9600, 19200, 38400, 57600, 115200, etc.)
- **Note**: Used for serial communication peripheral

**Examples**:
```scala
uartInitialBaud = 9600    // Slow, reliable
uartInitialBaud = 115200  // Standard
uartInitialBaud = 921600  // Fast (if supported)
```

#### programEntryPoint
- **Type**: `Long`
- **Default**: `0x80000000L`
- **Description**: Initial PC value after reset
- **Valid values**: Any 32-bit address, typically aligned to 4 bytes
- **Standard RISC-V addresses**:
  - `0x00000000`: Bottom of address space
  - `0x80000000`: Typical for RISC-V systems
  - Custom based on memory map

**Examples**:
```scala
programEntryPoint = 0x00000000L // Start at 0
programEntryPoint = 0x80000000L // RISC-V standard
programEntryPoint = 0x10000000L // Custom address
```

#### enableDebugInterface
- **Type**: `Boolean`
- **Default**: `false`
- **Description**: Enable hardware debug module
- **Valid values**: `true`, `false`
- **When enabled**: Adds halt/resume, register access, PC override
- **Trade-offs**:
  - Enabled: More area, better debugging
  - Disabled: Less area, production deployment

**Examples**:
```scala
enableDebugInterface = true  // Development
enableDebugInterface = false // Production
```

## Memory Map

The SoC memory map is determined by configuration:

### Default Memory Map (16 KB TCM)

```
0x00000000 - 0x7FFFFFFF : Reserved
0x80000000 - 0x80003FFF : TCM (if entry point is 0x80000000)
0x80004000 - 0xFFFFFFFF : Reserved
```

### Customizing Memory Map

To change memory regions, edit `src/main/scala/svarog/memory/TCM.scala`:

```scala
// Example: Map TCM to different base address
val baseAddr = 0x10000000L
when (io.req.bits.address >= baseAddr.U &&
      io.req.bits.address < (baseAddr + memSizeBytes).U) {
  // Access TCM
}
```

## Debug Interface

### When to Enable

**Enable (`true`) when**:
- Development and testing
- Hardware debugging needed
- Need to halt/inspect CPU state
- Using debugger tools

**Disable (`false`) when**:
- Production deployment
- Minimizing area
- No debug access needed
- Security concerns

### Debug Capabilities

When `enableDebugInterface = true`:

| Feature | Description |
|---------|-------------|
| **Halt** | Stop CPU execution |
| **Resume** | Continue from halt |
| **Single-step** | Execute one instruction |
| **Register read** | Inspect x0-x31 |
| **Register write** | Modify x0-x31 |
| **PC override** | Jump to arbitrary address |
| **Watchpoints** | Monitor store addresses |

### Debug Interface Signals

```scala
// When debug enabled
io.debug.halted: Bool           // CPU is halted
io.debug.halt: Bool             // Halt request
io.debug.resume: Bool           // Resume request
io.debug.step: Bool             // Single-step request
io.debug.debugPC: Valid[UInt]   // PC override
io.debug.regRead: RegFileReadIO // Read registers
io.debug.regWrite: RegFileWriteIO // Write registers
```

## Using Configurations

### In Verilog Generation

```scala
object GenerateVerilog extends App {
  val config = SvarogConfig(
    memSizeBytes = 32768,
    enableDebugInterface = true
  )

  emitVerilog(new SvarogSoC(config))
}
```

Run:
```bash
./mill svarog.runMain svarog.soc.GenerateVerilog
```

### In Tests

```scala
class MyTest extends AnyFlatSpec with ChiselScalatestTester {
  val testConfig = SvarogConfig(
    memSizeBytes = 4096,
    enableDebugInterface = true
  )

  it should "run test" in {
    test(new Cpu(testConfig)) { dut =>
      // Test code
    }
  }
}
```

### Multiple Configurations

Test multiple configurations:

```scala
val configs = Seq(
  SvarogConfig(memSizeBytes = 4096),
  SvarogConfig(memSizeBytes = 16384),
  SvarogConfig(memSizeBytes = 65536)
)

configs.foreach { config =>
  test(new Cpu(config)) { dut =>
    // Test code
  }
}
```
