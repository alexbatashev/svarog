# Makefile for CoreMark on Svarog SoC (RV32)

TARGET = coremark
TOOLCHAIN ?= riscv32-unknown-elf-

SRC_DIR = src
PORT_DIR = svarog

OUTPUT_BASE = ../../target/benchmarks

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

# Common flags
OPT ?= -O2
WARN ?= -Wall -Wextra
CFLAGS_COMMON = $(OPT) $(WARN) -ffreestanding -fno-builtin \
	-ffunction-sections -fdata-sections \
	-I$(SRC_DIR) -I$(PORT_DIR) \
	-DMAIN_HAS_NOARGC=1 -DITERATIONS=1000

RTC_HZ ?= 50000000

LDFLAGS_COMMON = -nostartfiles -nostdlib -Wl,--gc-sections

# Configuration definitions
# Format: config_name|toolchain_prefix|arch_flags|abi|linker_script
CONFIGS = \
	rv32i_zicsr_bootrom|$(TOOLCHAIN)|rv32i_zicsr|ilp32|$(PORT_DIR)/linker_rv32_bootrom.ld \
	rv32i_zicsr_ram|$(TOOLCHAIN)|rv32i_zicsr|ilp32|$(PORT_DIR)/linker_rv32_ram.ld \
	rv32i_zmmul_zicsr_bootrom|$(TOOLCHAIN)|rv32i_zmmul_zicsr|ilp32|$(PORT_DIR)/linker_rv32_bootrom.ld \
	rv32i_zmmul_zicsr_ram|$(TOOLCHAIN)|rv32i_zmmul_zicsr|ilp32|$(PORT_DIR)/linker_rv32_ram.ld \
	rv32im_zicsr_bootrom|$(TOOLCHAIN)|rv32im_zicsr|ilp32|$(PORT_DIR)/linker_rv32_bootrom.ld \
	rv32im_zicsr_ram|$(TOOLCHAIN)|rv32im_zicsr|ilp32|$(PORT_DIR)/linker_rv32_ram.ld \
	rv32im_zba_zbb_zicsr_bootrom|$(TOOLCHAIN)|rv32im_zba_zbb_zicsr|ilp32|$(PORT_DIR)/linker_rv32_bootrom.ld \
	rv32im_zba_zbb_zicsr_ram|$(TOOLCHAIN)|rv32im_zba_zbb_zicsr|ilp32|$(PORT_DIR)/linker_rv32_ram.ld

CONFIG_NAMES := $(foreach cfg,$(CONFIGS),$(word 1,$(subst |, ,$(cfg))))

all: $(CONFIG_NAMES)

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
	@echo "Compiling sources..."
	@FLAGS_STR="$(CFLAGS_COMMON) -DSVAROG_RTC_HZ=$(RTC_HZ) -march=$(3) -mabi=$(4)"; \
	$(2)gcc $(CFLAGS_COMMON) -DSVAROG_RTC_HZ=$(RTC_HZ) -march=$(3) -mabi=$(4) \
		-DFLAGS_STR="\"$$FLAGS_STR\"" \
		-c $(SRCS)
	@echo "Assembling startup..."
	@$(2)gcc -march=$(3) -mabi=$(4) -c $(ASMS)
	@echo "Linking $(TARGET).elf..."
	@$(2)gcc -march=$(3) -mabi=$(4) $(LDFLAGS_COMMON) \
		-Wl,-T,$(5) -Wl,-Map,$(OUTPUT_BASE)/$(1)/$(TARGET).map \
		-o $(OUTPUT_BASE)/$(1)/$(TARGET).elf \
		*.o
	@echo "Creating binary $(TARGET).bin..."
	@$(2)objcopy -O binary $(OUTPUT_BASE)/$(1)/$(TARGET).elf \
		$(OUTPUT_BASE)/$(1)/$(TARGET).bin
	@echo "Creating hex file $(TARGET).hex for Verilog $$readmemh..."
	@od -An -tx4 -v -w4 $(OUTPUT_BASE)/$(1)/$(TARGET).bin | tr -d ' ' > \
		$(OUTPUT_BASE)/$(1)/$(TARGET).hex
	@echo "Generating disassembly $(TARGET).lst..."
	@$(2)objdump -D $(OUTPUT_BASE)/$(1)/$(TARGET).elf > \
		$(OUTPUT_BASE)/$(1)/$(TARGET).lst
	@echo "Binary size:"
	@ls -lh $(OUTPUT_BASE)/$(1)/$(TARGET).bin
	@rm -f *.o
	@echo "Build complete: $(OUTPUT_BASE)/$(1)/"
	@echo ""

.PHONY: clean-$(1)
clean-$(1):
	@echo "Cleaning $(1)..."
	@rm -rf $(OUTPUT_BASE)/$(1)
endef

$(foreach cfg,$(CONFIGS),\
	$(eval CONFIG_NAME := $(word 1,$(subst |, ,$(cfg))))\
	$(eval TOOLCHAIN := $(word 2,$(subst |, ,$(cfg))))\
	$(eval ARCH := $(word 3,$(subst |, ,$(cfg))))\
	$(eval ABI := $(word 4,$(subst |, ,$(cfg))))\
	$(eval LINKER := $(word 5,$(subst |, ,$(cfg))))\
	$(eval $(call BUILD_CONFIG,$(CONFIG_NAME),$(TOOLCHAIN),$(ARCH),$(ABI),$(LINKER))))

clean: $(addprefix clean-,$(CONFIG_NAMES))
	@echo "All configurations cleaned"

clean-all: clean
	@echo "Removing output directory..."
	@rm -rf $(OUTPUT_BASE)

help:
	@echo "Svarog CoreMark Build System"
	@echo ""
	@echo "Build Configurations:"
	@$(foreach cfg,$(CONFIG_NAMES),echo "  $(cfg)";)
	@echo ""
	@echo "Targets:"
	@echo "  all          - Build all configurations (default)"
	@echo "  <config>     - Build a specific configuration"
	@echo "  clean        - Remove all build artifacts"
	@echo "  clean-all    - Remove all build artifacts and output directory"
	@echo "  clean-<cfg>  - Clean specific configuration"
	@echo "  help         - Display this help message"
	@echo ""
	@echo "Output directory: $(OUTPUT_BASE)/<config>/"
	@echo ""
	@echo "Output files (per configuration):"
	@echo "  $(TARGET).bin - Binary file for Svarog SoC"
	@echo "  $(TARGET).hex - Verilog hex file (compatible with \\$$readmemh)"
	@echo "  $(TARGET).elf - ELF executable with debug info"
	@echo "  $(TARGET).lst - Disassembly listing"
	@echo "  $(TARGET).map - Linker map file"

.PHONY: all clean clean-all help
