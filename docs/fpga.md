# Synthesizing FPGA bitstreams

## Verilog generation

```sh
./mill svarog.runMain svarog.VerilogGenerator --target-dir=target/generated/ --config=configs/svg-micro.yaml --bootloader=$PWD/target/examples/direct/rv32_bootrom/hello_world_uart.hex
```

## Vivado setup

Settings -> Synthesiz -> More options -> add:

```
-verilog_define ENABLE_INITIAL_MEM_=1
```

# Tested boards

- MicroPhase A7 Lite 200T (Xilinx Artix-7)
