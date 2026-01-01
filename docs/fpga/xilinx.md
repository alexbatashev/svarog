# Xilinx Flow (Artix‑7 reference)

Complete the common steps in `../fpga.md` first (boot ROM build and RTL generation). You should have:
- `target/examples/direct/rv32_bootrom/hello_world_uart.hex`
- Generated Verilog in `target/generated/`

## Reference top-level wrapper
File: `utils/fpga/xilinx/top.v`
- 50 MHz `clk`, active-low `rst_n`
- UART RX/TX mapped via SoC GPIOs
- Heartbeat LED toggled by a counter

Adapt clock/reset polarity or peripheral wiring if your board differs; keep the SoC reset wired as `reset(!rst_n)`.

## Pin constraints
Reference XDC for MicroPhase A7 Lite 200T: `utils/fpga/xilinx/microphase_a7_lite.xdc`
- `clk` J19, 50 MHz (`create_clock -period 20.000`)
- `rst_n` L18
- `led` M18
- `uart0_rxd` U2, `uart0_txd` V2

Copy/edit this file for other Xilinx boards (update pins and clock period).

## Vivado project steps
1. Create new **RTL Project** (no IP) targeting your Artix‑7 device.
2. Add sources: all `.sv` from `target/generated/` plus `utils/fpga/xilinx/top.v`.
3. Add constraints: your XDC (e.g., `microphase_a7_lite.xdc`).
4. Synthesis option: `Tools → Settings → Synthesis → Verilog Options → More Options` → append\
   `-verilog_define ENABLE_INITIAL_MEM_=1`\
   This preserves `$readmemh` ROM initialization in the bitstream.
5. Run **Synthesis → Implementation → Generate Bitstream**.

## Program and test
- Program the board via Hardware Manager using the generated `.bit`.
- Open a serial terminal at 115200 8N1 on UART0; “hello world” should appear after reset.
- LED should toggle about once per second (assumes 50 MHz clock).

## Porting notes
- Duplicate and edit the XDC for new pinouts/clock rates.
- Adjust `top.v` for LEDs, reset polarity, or peripheral wiring; keep SoC IO names unless you regenerate RTL with different IO definitions.
- Re-run the common RTL generation and Vivado steps.

## Tested hardware
- MicroPhase A7 Lite 200T (Artix‑7 XC7A200T)
