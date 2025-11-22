# Development Guide

This guide covers testing, debugging, and contributing to Svarog Micro.

## Development Setup

### Editor/IDE Setup

**IntelliJ IDEA** (Recommended):
1. Install Scala plugin
2. Import project as SBT/Mill project
3. Enable Chisel syntax highlighting

**VS Code**:
1. Install "Scala (Metals)" extension
2. Install "Scala Syntax (official)" extension
3. Open project folder

**Vim/Emacs**:
- Use Metals language server
- Install Scala syntax highlighting

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

### Test Examples

**Simple instruction test**:
```scala
test(new Cpu(config)) { dut =>
  // Load instruction: addi x1, x0, 5
  val inst = 0x00500093.U

  // Poke instruction into memory
  dut.io.imem.resp.valid.poke(true.B)
  dut.io.imem.resp.bits.readData(0).poke(inst(7, 0))
  dut.io.imem.resp.bits.readData(1).poke(inst(15, 8))
  dut.io.imem.resp.bits.readData(2).poke(inst(23, 16))
  dut.io.imem.resp.bits.readData(3).poke(inst(31, 24))

  // Step through pipeline
  for (i <- 0 until 10) dut.clock.step()

  // Verify register x1 = 5
  // (requires debug interface access)
}
```

**Hazard detection test**:
```scala
test(new HazardUnit) { dut =>
  // Setup: Execute writes x1, Decode reads x1
  dut.io.exec.rd.poke(1.U)
  dut.io.exec.regWrite.poke(true.B)
  dut.io.decode.rs1.poke(1.U)

  // Verify: Stall signal asserted
  dut.io.stall.expect(true.B)
}
```

## Debugging

### Waveform Generation

Enable VCD waveform dumping in tests:

```scala
test(new MyComponent).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
  // Test code
}
```

View waveforms with GTKWave:
```bash
gtkwave test_run_dir/MyComponentSpec/MyComponent.vcd
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

### Common Debug Scenarios

**Pipeline stall not clearing**:
1. Check hazard unit logic
2. Verify bypass paths
3. Ensure register write signals propagate

**Branch not taken**:
1. Check branch condition logic in Execute
2. Verify branch feedback valid signal
3. Check PC update priority in Fetch

**Memory request timeout**:
1. Verify memory request valid signal
2. Check memory response ready signal
3. Ensure memory model responds correctly

## Code Style

### Chisel Conventions

**Naming**:
- Modules: `PascalCase` (e.g., `class FetchStage`)
- Signals: `camelCase` (e.g., `val pcReg`)
- Constants: `camelCase` or `UPPER_CASE`

**Signal naming**:
- `_reg` suffix for registers (e.g., `pcReg`)
- `_next` suffix for next-cycle values
- No suffix for wires

**Bundles**:
- Use `Output` for all fields in custom bundles
- Group related signals into bundles

**Example**:
```scala
class MyBundle extends Bundle {
  val addr = Output(UInt(32.W))
  val data = Output(UInt(32.W))
  val valid = Output(Bool())
}
```

### Comments

**Module-level**:
```scala
/**
 * Fetch stage - fetches instructions from memory
 *
 * @param config SoC configuration
 */
class Fetch(config: SvarogConfig) extends Module {
  // ...
}
```

**Inline**:
```scala
// PC update priority: debug > branch > sequential
when (debugPC.valid) {
  pc := debugPC.bits
} .elsewhen (branchFeedback.valid) {
  pc := branchFeedback.bits.targetPC
} .otherwise {
  pc := pc + 4.U
}
```

## Adding New Features

### Adding New Instruction

1. **Add opcode** in `Opcodes.scala`:
```scala
val MY_OPCODE = "b1111111".U(7.W)
```

2. **Update decoder** in `BaseInstructions.scala`:
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

### Adding Pipeline Stage

1. Create new module in `src/main/scala/svarog/micro/`
2. Define input/output bundles
3. Add pipeline register (Queue) before/after stage
4. Update `Cpu.scala` to connect new stage
5. Add unit tests
6. Update documentation

### Adding Forwarding Path

1. **Identify source and destination** stages
2. **Add forwarding signals** to pipeline registers
3. **Implement bypass mux** in destination stage:
```scala
val forwardedData = Mux(forwardEnable && addrMatch,
  sourceData,  // Forwarded value
  normalData   // Normal path
)
```
4. **Add tests** for forwarding scenarios

## Contribution Workflow

### 1. Create Feature Branch

```bash
git checkout -b feature/my-new-feature
```

### 2. Make Changes

- Edit source files
- Add/update tests
- Update documentation if needed

### 3. Test Locally

```bash
# Compile
./mill svarog.compile

# Run tests
./mill svarog.test

# Run specific tests
./mill svarog.test.testOnly svarog.micro.MyNewSpec
```

### 4. Commit Changes

```bash
git add src/main/scala/svarog/...
git commit -m "feat: Add new feature description"
```

**Commit message format**:
- `feat:` - New feature
- `fix:` - Bug fix
- `docs:` - Documentation
- `test:` - Test changes
- `refactor:` - Code refactoring
- `perf:` - Performance improvement

### 5. Push and Create PR

```bash
git push origin feature/my-new-feature
```

Create pull request on GitHub with:
- Clear description
- List of changes
- Test results
- Any breaking changes

## Performance Analysis

### Measuring CPI

Add performance counters:

```scala
val cycleCount = RegInit(0.U(64.W))
val instCount = RegInit(0.U(64.W))

cycleCount := cycleCount + 1.U
when (io.wb.valid && io.wb.bits.regWrite) {
  instCount := instCount + 1.U
}

// CPI = cycleCount / instCount
```

### Identifying Bottlenecks

1. **High CPI**: Check for frequent hazards/stalls
2. **Low IPC**: Look for pipeline bubbles
3. **Long critical path**: Review combinational logic depth

### Profiling Tools

**Chisel coverage**:
```scala
import chisel3.coverage._
cover(condition, "coverage_point_name")
```

**Verilator profiling**:
```bash
verilator --prof-cfuncs -CFLAGS -pg ...
```

## Synthesis Notes

### Area Optimization

- Minimize register count
- Share logic between paths
- Use muxes instead of replicated logic

### Timing Optimization

- Pipeline critical paths
- Register outputs of complex logic
- Avoid long combinational chains

### FPGA-Specific

**For Xilinx FPGAs**:
- Use BRAM for register file (>32 registers)
- Leverage DSP blocks for multipliers
- Consider carry chain optimization

**For Intel FPGAs**:
- Use M10K/M20K blocks for memory
- Leverage ALM packing

## Resources

### Chisel Resources
- [Chisel Bootcamp](https://github.com/freechipsproject/chisel-bootcamp)
- [Chisel API Docs](https://www.chisel-lang.org/api/)
- [ChiselTest Guide](https://github.com/ucb-bar/chiseltest)

### RISC-V Resources
- [RISC-V Spec](https://riscv.org/technical/specifications/)
- [RISC-V Assembly Reference](https://github.com/riscv-non-isa/riscv-asm-manual)

### Pipeline Design
- [Computer Architecture (Patterson & Hennessy)](https://www.elsevier.com/books/computer-organization-and-design-risc-v-edition/patterson/978-0-12-820331-6)

## Common Issues

**Tests fail with timeout**:
- Check for infinite loops in pipeline
- Verify clock stepping in test
- Ensure valid/ready handshaking completes

**Synthesis fails**:
- Check for uninitialized registers
- Verify all signals are driven
- Look for combinational loops

**Waveform shows X (unknown)**:
- Signal not initialized
- Reset not asserted
- Conditional assignment incomplete

## Getting Help

- Review existing tests for examples
- Check architecture documentation
- Ask in project issues/discussions
- Consult Chisel community for HDL questions
