# Makefile for CoreMark on Svarog SoC (RV32)

TARGET = coremark
TOOLCHAIN ?= riscv32-unknown-elf-

PROJECT_ROOT ?= $(abspath $(CURDIR)/../..)

SRC_DIR = src
PORT_DIR = svarog

MARCH ?= rv32im
MABI ?= ilp32
VARIANT ?= ram
OUTPUT_PATH ?= $(PROJECT_ROOT)/target/benchmarks/coremark
RTC_HZ ?= 50000000
ITERATIONS ?= 1000

ifeq ($(origin PATH),command line)
OUTPUT_PATH := $(PATH)
endif

SRCS = \
	$(SRC_DIR)/core_list_join.c \
	$(SRC_DIR)/core_main.c \
	$(SRC_DIR)/core_matrix.c \
	$(SRC_DIR)/core_state.c \
	$(SRC_DIR)/core_util.c \
	$(PORT_DIR)/core_portme.c \
	$(PORT_DIR)/ee_printf.c \
	$(PORT_DIR)/softarith.c

ASMS = \
	$(PORT_DIR)/crt0.S

OPT ?= -O2
WARN ?= -Wall -Wextra
CFLAGS_COMMON = $(OPT) $(WARN) -ffreestanding -fno-builtin \
	-ffunction-sections -fdata-sections \
	-I$(SRC_DIR) -I$(PORT_DIR) \
	-DMAIN_HAS_NOARGC=1 -DITERATIONS=$(ITERATIONS)

LDFLAGS_COMMON = -nostartfiles -nostdlib -Wl,--gc-sections

VARIANT_LDSCRIPT_ram = $(PORT_DIR)/linker_rv32_ram.ld
VARIANT_LDSCRIPT_bootloader = $(PORT_DIR)/linker_rv32_bootrom.ld
LINKER_SCRIPT := $(VARIANT_LDSCRIPT_$(VARIANT))

ifeq ($(LINKER_SCRIPT),)
$(error VARIANT must be one of: ram bootloader)
endif

CONFIG_NAME := $(MARCH)_$(VARIANT)
OUTPUT_DIR := $(OUTPUT_PATH)/$(CONFIG_NAME)

ARCH_FLAGS = -march=$(MARCH) -mabi=$(MABI)
FLAGS_STR = $(CFLAGS_COMMON) -DSVAROG_RTC_HZ=$(RTC_HZ) $(ARCH_FLAGS)
CFLAGS = $(CFLAGS_COMMON) -DSVAROG_RTC_HZ=$(RTC_HZ) $(ARCH_FLAGS) \
	-DFLAGS_STR="\"$(FLAGS_STR)\""

all: build

.PHONY: build
build:
	@echo "========================================"
	@echo "Building CoreMark"
	@echo "  Architecture: $(MARCH)"
	@echo "  ABI: $(MABI)"
	@echo "  Variant: $(VARIANT)"
	@echo "  Linker script: $(LINKER_SCRIPT)"
	@echo "  Output dir: $(OUTPUT_DIR)"
	@echo "========================================"
	@mkdir -p $(OUTPUT_DIR)
	@echo "Compiling sources..."
	@$(TOOLCHAIN)gcc $(CFLAGS) -c $(SRCS)
	@echo "Assembling startup..."
	@$(TOOLCHAIN)gcc $(ARCH_FLAGS) -c $(ASMS)
	@echo "Linking $(TARGET).elf..."
	@$(TOOLCHAIN)gcc $(ARCH_FLAGS) $(LDFLAGS_COMMON) \
		-Wl,-T,$(LINKER_SCRIPT) -Wl,-Map,$(OUTPUT_DIR)/$(TARGET).map \
		-o $(OUTPUT_DIR)/$(TARGET).elf \
		*.o
	@echo "Creating binary $(TARGET).bin..."
	@$(TOOLCHAIN)objcopy -O binary $(OUTPUT_DIR)/$(TARGET).elf \
		$(OUTPUT_DIR)/$(TARGET).bin
	@echo "Creating hex file $(TARGET).hex for Verilog $$readmemh..."
	@od -An -tx4 -v -w4 $(OUTPUT_DIR)/$(TARGET).bin | tr -d ' ' > \
		$(OUTPUT_DIR)/$(TARGET).hex
	@echo "Generating disassembly $(TARGET).lst..."
	@$(TOOLCHAIN)objdump -D $(OUTPUT_DIR)/$(TARGET).elf > \
		$(OUTPUT_DIR)/$(TARGET).lst
	@echo "Binary size:"
	@ls -lh $(OUTPUT_DIR)/$(TARGET).bin
	@rm -f *.o
	@echo "Build complete: $(OUTPUT_DIR)/"
	@echo ""

.PHONY: clean
clean:
	@echo "Cleaning $(CONFIG_NAME)..."
	@rm -rf $(OUTPUT_DIR)

.PHONY: clean-all
clean-all: clean
	@echo "Removing output directory..."
	@rm -rf $(OUTPUT_PATH)

help:
	@echo "Svarog CoreMark Build System"
	@echo ""
	@echo "Targets:"
	@echo "  all          - Build CoreMark (default)"
	@echo "  build        - Build CoreMark"
	@echo "  clean        - Remove build artifacts for current config"
	@echo "  clean-all    - Remove all build artifacts"
	@echo "  help         - Display this help message"
	@echo ""
	@echo "Config variables:"
	@echo "  MARCH=$(MARCH)"
	@echo "  MABI=$(MABI)"
	@echo "  VARIANT=$(VARIANT) (ram or bootloader)"
	@echo "  ITERATIONS=$(ITERATIONS)"
	@echo "  OUTPUT_PATH=$(OUTPUT_PATH) (or PATH=... on command line)"
	@echo ""
	@echo "Output directory: $(OUTPUT_DIR)/"
	@echo ""
	@echo "Output files:"
	@echo "  $(TARGET).bin - Binary file for Svarog SoC"
	@echo "  $(TARGET).hex - Verilog hex file (compatible with \$$readmemh)"
	@echo "  $(TARGET).elf - ELF executable with debug info"
	@echo "  $(TARGET).lst - Disassembly listing"
	@echo "  $(TARGET).map - Linker map file"

.PHONY: all help
