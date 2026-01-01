# FPGA Bitstreams Overview

RTL generation is shared across all vendors. Vendor-specific steps (top-level wrapper, constraints, tool flow) live in separate guides.

## Common steps (all vendors)
1. **Build boot ROM image**  
   Minimal hello-world UART image:
   ```bash
   make -C examples/direct rv32_bootrom
   ```
   Output: `target/examples/direct/rv32_bootrom/hello_world_uart.hex`

2. **Generate RTL**  
   ```bash
   ./mill svarog.runMain svarog.VerilogGenerator \
     --config=configs/svg-micro.yaml \
     --target-dir=target/generated \
     --bootloader=$PWD/target/examples/direct/rv32_bootrom/hello_world_uart.hex
   ```
   Flags:  
   - `--config`: SoC YAML (cores/peripherals/memory)  
   - `--target-dir`: output for generated Verilog (default `target/generated`)  
   - `--bootloader`: embeds ROM contents (omit to leave ROM empty)  
   - `--simulator-debug-iface=true`: Verilator only; keep `false` for FPGA

## Vendor guides
- [Xilinx Artix‑7](fpga/xilinx.md) — implemented and tested.
- Other vendors — TBD (follow the common steps, then adapt top-level and constraints).
