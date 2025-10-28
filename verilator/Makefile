# Verilator Makefile for CPU simulation

# Verilator executable
VERILATOR = verilator

# Verilator flags
VFLAGS = -Wall --cc --exe --build
VFLAGS += --trace  # Enable waveform tracing (VCD)
VFLAGS += -Wno-fatal  # Don't stop on warnings
VFLAGS += -Wno-DECLFILENAME  # Ignore filename warnings
VFLAGS += -Wno-WIDTH  # Ignore width warnings
VFLAGS += --debug  # Enable debug features
# Add include paths
VFLAGS += -I$(VERILOG_DIR)

# Directories
VERILOG_DIR = ../generated
BUILD_DIR = obj_dir

# Source files
VERILOG_SRC = Cpu_verilator.sv
CPP_TB = tb_cpu.cpp

# Top module
TOP_MODULE = Cpu

# Default target
all: sim

# Prepare Verilog (strip verification layers)
$(VERILOG_SRC): $(VERILOG_DIR)/Cpu.sv prepare_verilog.sh
	@echo "Preparing Verilog for Verilator..."
	./prepare_verilog.sh

# Build the simulator
sim: $(VERILOG_SRC) $(CPP_TB)
	@echo "Building Verilator simulation..."
	$(VERILATOR) $(VFLAGS) --top-module $(TOP_MODULE) $(VERILOG_SRC) $(CPP_TB)
	@echo "Build complete! Executable: $(BUILD_DIR)/V$(TOP_MODULE)"

# Run the simulation
run: sim
	@echo "\n=== Running simulation ==="
	./$(BUILD_DIR)/V$(TOP_MODULE)

# Build traced version with hazard test
sim-trace: $(VERILOG_SRC) tb_cpu_trace.cpp
	@echo "Building traced simulation..."
	$(VERILATOR) $(VFLAGS) --top-module $(TOP_MODULE) $(VERILOG_SRC) tb_cpu_trace.cpp
	@echo "Build complete!"

# Run traced simulation
run-trace: sim-trace
	@echo "\n=== Running simulation with waveform trace ==="
	./$(BUILD_DIR)/V$(TOP_MODULE)
	@echo "\nOpen waveform with: gtkwave cpu_trace.vcd"

# Build load/store test with tracing
sim-loadstore: $(VERILOG_SRC) tb_cpu_loadstore.cpp
	@echo "Building load/store test with tracing..."
	$(VERILATOR) $(VFLAGS) --top-module $(TOP_MODULE) $(VERILOG_SRC) tb_cpu_loadstore.cpp
	@echo "Build complete!"

# Run load/store test
run-loadstore: sim-loadstore
	@echo "\n=== Running Load/Store Test with Waveform Trace ==="
	./$(BUILD_DIR)/V$(TOP_MODULE)
	@echo "\nOpen waveform with: gtkwave cpu_loadstore.vcd"

# Clean build artifacts
clean:
	rm -rf $(BUILD_DIR)
	rm -f *.vcd
	rm -f Cpu_verilator.sv
	@echo "Cleaned all build artifacts"

# Help
help:
	@echo "Verilator CPU Simulation Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  all            - Build the simulator (default)"
	@echo "  run            - Build and run simple ADDI test"
	@echo "  run-trace      - Build and run hazard test with VCD waveform"
	@echo "  run-loadstore  - Build and run load/store test with VCD waveform"
	@echo "  clean          - Remove build artifacts"
	@echo "  help           - Show this help message"

.PHONY: all sim run run-trace sim-trace sim-loadstore run-loadstore clean help
