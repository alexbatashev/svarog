# Makefile for RV32I Hello World UART program

# Toolchain configuration
CROSS_PREFIX = riscv32-unknown-elf-
AS = $(CROSS_PREFIX)as
LD = $(CROSS_PREFIX)ld
OBJCOPY = $(CROSS_PREFIX)objcopy
OBJDUMP = $(CROSS_PREFIX)objdump

# Source files
ASM_SOURCES = hello_world_uart.S
LINKER_SCRIPT = linker.ld

# Output files
TARGET = hello_world_uart
ELF = $(TARGET).elf
BIN = $(TARGET).rv32.bin
MAP = $(TARGET).map
LST = $(TARGET).lst

# Flags
ASFLAGS = -march=rv32i -mabi=ilp32
LDFLAGS = -T$(LINKER_SCRIPT) -Map=$(MAP) --print-memory-usage

# Object files
OBJECTS = $(ASM_SOURCES:.S=.o)

# Default target
all: $(BIN) $(LST)

# Compile assembly to object file
%.o: %.S
	@echo "Assembling $<..."
	$(AS) $(ASFLAGS) -o $@ $<

# Link object files to ELF
$(ELF): $(OBJECTS) $(LINKER_SCRIPT)
	@echo "Linking $@..."
	$(LD) $(LDFLAGS) -o $@ $(OBJECTS)

# Convert ELF to binary
$(BIN): $(ELF)
	@echo "Creating binary $@..."
	$(OBJCOPY) -O binary $< $@
	@echo "Binary size:"
	@ls -lh $(BIN)

# Generate disassembly listing
$(LST): $(ELF)
	@echo "Generating disassembly $@..."
	$(OBJDUMP) -D $< > $@

# Clean build artifacts
clean:
	@echo "Cleaning..."
	rm -f $(OBJECTS) $(ELF) $(BIN) $(MAP) $(LST)

# Phony targets
.PHONY: all clean

# Display help
help:
	@echo "Svarog RV32I Hello World UART Build System"
	@echo ""
	@echo "Targets:"
	@echo "  all     - Build the binary (default)"
	@echo "  clean   - Remove all build artifacts"
	@echo "  help    - Display this help message"
	@echo ""
	@echo "Output files:"
	@echo "  $(BIN) - Binary file for Svarog SoC"
	@echo "  $(ELF) - ELF executable with debug info"
	@echo "  $(LST) - Disassembly listing"
	@echo "  $(MAP) - Linker map file"
