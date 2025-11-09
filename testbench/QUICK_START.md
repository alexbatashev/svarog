# Quick Start Guide

## What Was Fixed

1. **Fixed Scala build errors** in `GenerateVerilatorTop.scala`
   - Removed reference to undefined `Artix7Top.programWords`
   - Simplified to use `emitVerilog` like other generators

2. **Created dynamic program loading infrastructure**:
   - `LoadableInstructionRom`: ROM with write port for testbench access
   - `VerilatorSoC`: SoC variant that exposes ROM write signals
   - Updated `VerilatorTop`: Exposes ROM write ports to testbench

3. **Set up Rust/Verilator testbench**:
   - Example tests in `testbench/src/lib.rs`
   - `build.rs` automatically generates Verilog during `cargo build`
   - Uses `marlin` crate for Verilator bindings

## How to Use

### 1. Generate Verilog (one time)

```bash
# This happens automatically during cargo build, or run manually:
mill svarog.runMain svarog.GenerateVerilatorTop
```

### 2. Write a Test

```rust
#[test]
fn my_test() {
    let program = vec![
        0x00500093, // addi x1, x0, 5
        0x00700113, // addi x2, x0, 7
        0x002081b3, // add x3, x1, x2
    ];

    let mut dut = VerilatorTop::new();
    dut.reset = 1;
    dut.eval();

    // Load program via ROM write port
    load_program(&mut dut, &program);

    dut.reset = 0;
    run_cycles(&mut dut, 100);
}
```

### 3. Run Tests

```bash
cargo test -- --nocapture
```

## Key Insight

The ROM write port (`rom_write_en`, `rom_write_addr`, `rom_write_data`) allows you to:
- Load different programs **without regenerating Verilog**
- Test hundreds of programs quickly
- Cross-validate with Spike efficiently

## What's Next?

You mentioned you want to:
1. Compile many small programs
2. Run them under Verilator (✅ now possible!)
3. Run them under Spike
4. Cross-validate results

The infrastructure is now in place for steps 1-2. For steps 3-4, you'll want to:
- Create a Spike wrapper similar to the Verilator wrapper
- Compare PC traces and register write traces
- Flag any discrepancies

Let me know when you're ready for the next phase!
