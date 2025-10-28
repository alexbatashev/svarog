#!/bin/bash

# Script to prepare Chisel-generated Verilog for Verilator
# Removes verification layer includes that cause issues

INPUT="../generated/Cpu.sv"
OUTPUT="Cpu_verilator.sv"

echo "Preparing Verilog for Verilator..."

# Comment out the verification includes
sed 's/`include "verification\/layers-Cpu-Verification.sv"/\/\/ `include "verification\/layers-Cpu-Verification.sv" \/\/ Disabled for Verilator/g' "$INPUT" > "$OUTPUT"

echo "Created $OUTPUT with verification includes disabled"
echo "Ready for Verilator!"
