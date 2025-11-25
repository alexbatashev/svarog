# Getting Started with Svarog Micro

This guide will help you set up your development environment and build the Svarog Micro core.

## Prerequisites

### Required

- **JDK 11 or newer** - Download from [Adoptium](https://adoptium.net/)
- **Scala Build Tool** - Either SBT or Mill (Mill bootstrap included)
- **Git** - For version control

### Optional (for simulation/synthesis)

- **Verilator** - For Verilog simulation ([install guide](https://verilator.org/guide/latest/install.html))
- **Vivado/Quartus** - For FPGA synthesis (if targeting hardware)

## Installation

### 1. Clone the Repository

```bash
git clone https://github.com/alexbatashev/svarog.git
cd svarog
```

### 2. Verify JDK Installation

```bash
java -version
# Should show Java 11 or newer
```

### 3. Choose Your Build Tool

#### Option A: Mill (Recommended)

Mill is included via bootstrap script:

```bash
./mill version
```

No installation needed!

#### Option B: SBT

Download from [scala-sbt.org](https://www.scala-sbt.org/download.html):

```bash
# macOS (Homebrew)
brew install sbt

# Linux (Debian/Ubuntu)
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
sudo apt-get update
sudo apt-get install sbt
```

## Building

### Compile the Project

```bash
# Using Mill
./mill svarog.compile

# Using SBT
sbt compile
```

### Generate Verilog

```bash
# Generate Verilog for Verilator testbench
./mill -i svarog.runMain svarog.GenerateVerilatorTop --target-dir=target/generated/
```

See the root README for available command-line options.

## Running Tests

### Run All Tests

```bash
# Using Mill
./mill svarog.test

# Using SBT
sbt test
```

### Run Specific Test Suite

```bash
# Using Mill
./mill svarog.test.testOnly svarog.micro.PipelineSpec
./mill svarog.test.testOnly svarog.micro.ExecuteSpec

# Using SBT
sbt "testOnly svarog.micro.PipelineSpec"
sbt "testOnly svarog.micro.ExecuteSpec"
```

### Available Test Suites

- `PipelineSpec` - Full pipeline integration tests
- `FetchSpec` - Instruction fetch unit tests
- `ExecuteSpec` - Execute stage tests
- `SimpleDecoderSpec` - Decoder tests
- `CpuDebugSpec` - Debug interface tests
- `TCMSpec` - Memory tests

## Project Configuration

### SvarogConfig Parameters

Edit `src/main/scala/svarog/soc/SvarogConfig.scala`:

```scala
case class SvarogConfig(
  xlen: Int = 32,                    // Data width (fixed for RV32)
  memSizeBytes: Int = 16384,         // 16KB memory
  clockHz: Int = 50_000_000,         // 50 MHz clock
  uartInitialBaud: Int = 115200,     // UART baud rate
  programEntryPoint: Long = 0x80000000L,  // Reset PC
  enableDebugInterface: Boolean = true    // Enable debug
)
```

### Customizing for Your Use Case

**For simulation/testing:**
```scala
SvarogConfig(
  memSizeBytes = 1024 * 1024,  // 1MB for larger programs
  enableDebugInterface = true   // Enable debugging
)
```

**For FPGA deployment:**
```scala
SvarogConfig(
  memSizeBytes = 8192,         // Fit in BRAM
  clockHz = 100_000_000,       // Match your clock
  enableDebugInterface = false // Reduce area
)
```

## Development Workflow

### 1. Make Changes

Edit Chisel source files in `src/main/scala/svarog/`

### 2. Compile

```bash
./mill svarog.compile
```

### 3. Run Tests

```bash
./mill svarog.test
```

### 4. Debug Failures

```bash
# Run single failing test with verbose output
./mill svarog.test.testOnly svarog.micro.PipelineSpec -- -oF
```

### 5. Generate Verilog

```bash
./mill svarog.runMain svarog.soc.SvarogSoC
```

## Common Issues

### "Java version too old"

**Solution**: Install JDK 11 or newer

```bash
# Check version
java -version

# Update JAVA_HOME if needed
export JAVA_HOME=/path/to/jdk-11
```

### "sbt: command not found"

**Solution**: Use the included Mill bootstrap instead:

```bash
./mill svarog.test
```

### Verilator Errors

**Solution**: Ensure Verilator 4.0+ is installed:

```bash
verilator --version

# Install on Ubuntu/Debian
sudo apt-get install verilator

# Install on macOS
brew install verilator
```

### Out of Memory During Build

**Solution**: Increase JVM heap size:

```bash
# For Mill
export JAVA_OPTS="-Xmx4G"
./mill svarog.compile

# For SBT
sbt -mem 4096 compile
```

## Next Steps

- Read the [Architecture Guide](architecture.md) to understand the pipeline
- Check the [Development Guide](development.md) for contribution guidelines
- Review [Configuration Options](configuration.md) for customization
- Explore test files in `src/test/scala/svarog/` for examples

## Getting Help

- **Issues**: [GitHub Issues](https://github.com/alexbatashev/svarog/issues)
- **Chisel Help**: [Chisel Gitter](https://gitter.im/freechipsproject/chisel3)
- **RISC-V**: [RISC-V Specifications](https://riscv.org/technical/specifications/)
