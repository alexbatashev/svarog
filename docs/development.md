# Development Guide

This guide covers testing, debugging, and contributing to Svarog Micro.

## Testing

### Test Organization

```
src/test/scala/svarog/
├── micro/              # Pipeline tests
│   ├── PipelineSpec.scala      # Full pipeline integration
│   ├── FetchSpec.scala         # Fetch stage unit tests
│   ├── ExecuteSpec.scala       # Execute stage unit tests
│   └── CpuDebugSpec.scala      # Debug interface tests
├── decoder/
│   └── SimpleDecoderSpec.scala # Decoder tests
└── memory/
    └── TCMSpec.scala           # Memory tests
```

### Running Tests

**All tests**:
```bash
./mill svarog.test
```

**Specific test suite**:
```bash
./mill svarog.test.testOnly svarog.micro.PipelineSpec
```

**Single test case**:
```bash
./mill svarog.test.testOnly svarog.micro.PipelineSpec -- -z "should execute dependent ADDI"
```

**With verbose output**:
```bash
./mill svarog.test -- -oF
```

### Writing Tests

Tests use **ChiselTest** framework. Example structure:

```scala
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class MyComponentSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "MyComponent"

  it should "perform basic operation" in {
    test(new MyComponent) { dut =>
      // Setup
      dut.io.input.poke(42.U)
      dut.clock.step()

      // Verify
      dut.io.output.expect(84.U)
    }
  }
}
```

**Key ChiselTest Operations**:
- `poke(value)` - Set input signal
- `expect(value)` - Check output signal
- `peek()` - Read signal value
- `clock.step(n)` - Advance clock n cycles
- `reset.poke(true.B)` - Assert reset

## Debugging

### Waveform Generation

If `targets/vcd` directory exists, waveforms will be saved there.

View waveforms with GTKWave:
```bash
gtkwave target/rv32ui-simple.vcd
```

### Print Debugging

Use `printf` in Chisel code:

```scala
printf(p"PC: 0x${Hexadecimal(pc)}, Inst: 0x${Hexadecimal(instruction)}\n")
```

**Note**: Prints only appear during simulation, not in synthesized hardware.

### Chisel Assertions

Add runtime checks:

```scala
assert(rd =/= 0.U || !regWrite, "Cannot write to x0")
```

## Adding New Features

### Adding New Instruction

1. **Add opcode** in `Opcodes.scala`:
```scala
val MY_OPCODE = "b1111111".U(7.W)
```

2. **Update decoder** in `decoder/*Instructions.scala`:
```scala
is(Opcodes.MY_OPCODE) {
  opType := OpType.MY_TYPE
  // Set control signals
}
```

3. **Handle in Execute** in `Execute.scala`:
```scala
when (io.uop.bits.opType === OpType.MY_TYPE) {
  // Implement operation
}
```

4. **Add tests** in `SimpleDecoderSpec.scala` and `ExecuteSpec.scala`
