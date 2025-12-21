# Makefile for RV32I/RV64I Hello World UART program
# Supports multiple build configurations with different architectures and memory layouts

# Source files
ASM_SOURCES = hello_world_uart.S
TARGET = hello_world_uart

# Output directory
OUTPUT_BASE = ../../target/examples/direct

# Configuration definitions
# Format: config_name|toolchain_prefix|arch_flags|abi|linker_script
CONFIGS = \
	rv32_bootrom|riscv32-unknown-elf-|rv32i|ilp32|linker_rv32_bootrom.ld \
	rv32_ram|riscv32-unknown-elf-|rv32i|ilp32|linker_rv32_ram.ld \
	rv64_bootrom|riscv64-unknown-elf-|rv64i|lp64|linker_rv64_bootrom.ld \
	rv64_ram|riscv64-unknown-elf-|rv64i|lp64|linker_rv64_ram.ld

# Extract configuration names for phony targets
CONFIG_NAMES := $(foreach cfg,$(CONFIGS),$(word 1,$(subst |, ,$(cfg))))

# Default target - build all configurations
all: $(CONFIG_NAMES)

# Template for building a configuration
# Arguments: config_name, toolchain_prefix, arch, abi, linker_script
define BUILD_CONFIG
.PHONY: $(1)
$(1):
	@echo "========================================"
	@echo "Building configuration: $(1)"
	@echo "  Architecture: $(3)"
	@echo "  ABI: $(4)"
	@echo "  Linker script: $(5)"
	@echo "========================================"
	@mkdir -p $(OUTPUT_BASE)/$(1)
	@echo "Assembling $(ASM_SOURCES)..."
	@$(2)as -march=$(3) -mabi=$(4) -o $(OUTPUT_BASE)/$(1)/$(TARGET).o $(ASM_SOURCES)
	@echo "Linking $(TARGET).elf..."
	@$(2)ld -T$(5) -Map=$(OUTPUT_BASE)/$(1)/$(TARGET).map --print-memory-usage \
		-o $(OUTPUT_BASE)/$(1)/$(TARGET).elf $(OUTPUT_BASE)/$(1)/$(TARGET).o
	@echo "Creating binary $(TARGET).bin..."
	@$(2)objcopy -O binary $(OUTPUT_BASE)/$(1)/$(TARGET).elf $(OUTPUT_BASE)/$(1)/$(TARGET).bin
	@echo "Generating disassembly $(TARGET).lst..."
	@$(2)objdump -D $(OUTPUT_BASE)/$(1)/$(TARGET).elf > $(OUTPUT_BASE)/$(1)/$(TARGET).lst
	@echo "Binary size:"
	@ls -lh $(OUTPUT_BASE)/$(1)/$(TARGET).bin
	@echo "Build complete: $(OUTPUT_BASE)/$(1)/"
	@echo ""

.PHONY: clean-$(1)
clean-$(1):
	@echo "Cleaning $(1)..."
	@rm -rf $(OUTPUT_BASE)/$(1)
endef

# Generate build rules for each configuration
$(foreach cfg,$(CONFIGS),\
	$(eval CONFIG_NAME := $(word 1,$(subst |, ,$(cfg))))\
	$(eval TOOLCHAIN := $(word 2,$(subst |, ,$(cfg))))\
	$(eval ARCH := $(word 3,$(subst |, ,$(cfg))))\
	$(eval ABI := $(word 4,$(subst |, ,$(cfg))))\
	$(eval LINKER := $(word 5,$(subst |, ,$(cfg))))\
	$(eval $(call BUILD_CONFIG,$(CONFIG_NAME),$(TOOLCHAIN),$(ARCH),$(ABI),$(LINKER))))

# Clean all configurations
clean: $(addprefix clean-,$(CONFIG_NAMES))
	@echo "All configurations cleaned"

# Clean everything including output directory
clean-all: clean
	@echo "Removing output directory..."
	@rm -rf $(OUTPUT_BASE)

# Display help
help:
	@echo "Svarog Hello World UART Build System"
	@echo ""
	@echo "Build Configurations:"
	@echo "  rv32_bootrom - RV32I with code in ROM at 0x00480000"
	@echo "  rv32_ram     - RV32I with code in RAM at 0x80000000"
	@echo "  rv64_bootrom - RV64I with code in ROM at 0x00480000"
	@echo "  rv64_ram     - RV64I with code in RAM at 0x80000000"
	@echo ""
	@echo "Targets:"
	@echo "  all          - Build all configurations (default)"
	@echo "  rv32_bootrom - Build only RV32I bootrom configuration"
	@echo "  rv32_ram     - Build only RV32I RAM configuration"
	@echo "  rv64_bootrom - Build only RV64I bootrom configuration"
	@echo "  rv64_ram     - Build only RV64I RAM configuration"
	@echo "  clean        - Remove all build artifacts"
	@echo "  clean-all    - Remove all build artifacts and output directory"
	@echo "  clean-<cfg>  - Clean specific configuration"
	@echo "  help         - Display this help message"
	@echo ""
	@echo "Output directory: $(OUTPUT_BASE)/<config>/"
	@echo ""
	@echo "Output files (per configuration):"
	@echo "  $(TARGET).bin - Binary file for Svarog SoC"
	@echo "  $(TARGET).elf - ELF executable with debug info"
	@echo "  $(TARGET).lst - Disassembly listing"
	@echo "  $(TARGET).map - Linker map file"

.PHONY: all clean clean-all help
