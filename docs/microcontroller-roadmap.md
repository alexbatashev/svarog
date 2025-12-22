# Svarog Microcontroller Roadmap
## Path to Competitive Low-End Embedded MCU

**Target Market**: Cortex-M class applications (embedded, automotive, IoT, smart home)
**Document Version**: 1.1
**Date**: 2025-12-22
**Changelog**: Updated with actual resource measurements (5k LUTs), M extension timing issues, and realistic development timeline based on commit history

---

## Executive Summary

### Current State
Svarog is a **solid RISC-V RV32I_Zicsr core** with:
- ✅ Complete 5-stage pipeline with hazard handling
- ✅ Full RV32I base integer ISA (38 instructions)
- ✅ Zicsr extension (CSR operations)
- ✅ Basic peripherals (2× UART)
- ✅ Memory subsystem (64KB TCM, ROM bootloader, Wishbone bus)
- ✅ Debug interface (halt/resume, register access, breakpoints)
- ✅ Configuration-driven architecture (YAML-based)
- ✅ **~5000 LUTs on Artix-7** (measured without M extension)

**M Extension Status**: ⚠️ Implemented but has timing failures
- Issue: Combinational 32×32 multiply and divide operations
- Current implementation cannot meet 50 MHz timing
- **Needs rework**: Pipelined multiplier + iterative divider (Priority: Phase 0)

### Target State
A **competitive low-end microcontroller** comparable to ARM Cortex-M0+/M3 with:
- ⚡ Interrupt/exception handling (RISC-V Machine mode)
- ⚡ Rich peripheral set (GPIO, Timers, SPI, I2C, ADC, PWM)
- ⚡ Power management and low-power modes
- ⚡ DMA for autonomous data transfers
- ⚡ Watchdog and system monitoring
- ⚡ Real-time capabilities with deterministic interrupts
- ⚡ Memory protection (PMP - Physical Memory Protection)
- ⚡ Security features for IoT applications

### Gap Analysis
**Critical Missing Features** (prevent embedded use):
1. No interrupt/exception handling
2. No timers (system tick, general purpose)
3. No GPIO for I/O control
4. No power management

**Competitive Features Needed**:
5. SPI/I2C for sensor communication
6. ADC for analog sensing
7. PWM for motor/LED control
8. DMA for efficient data movement
9. Watchdog timer for reliability
10. RTC for timekeeping

---

## Market Comparison: Cortex-M vs Svarog

### ARM Cortex-M0+ (Entry Level)
| Feature | Cortex-M0+ | Svarog Current | Gap |
|---------|------------|----------------|-----|
| **Core** | 32-bit, 2-stage pipeline | 32-bit, 5-stage pipeline | ✅ |
| **ISA** | Thumb-2 (56 instructions) | RV32IM (full) | ✅ |
| **Freq** | Up to 50 MHz | ~50 MHz target | ✅ |
| **Interrupts** | NVIC (1-32 interrupts) | None | ❌ |
| **Timers** | SysTick + vendor timers | None | ❌ |
| **GPIO** | Vendor-specific | None | ❌ |
| **SPI/I2C** | Vendor-specific | None | ❌ |
| **UART** | Vendor-specific | 2× UART | ✅ |
| **DMA** | Optional | None | ❌ |
| **WFI/WFE** | Yes | None | ❌ |
| **Debug** | SWD/JTAG | Custom | ✅ |

### ARM Cortex-M3 (Mid-Range)
| Feature | Cortex-M3 | Svarog Target | Priority |
|---------|-----------|---------------|----------|
| **Pipeline** | 3-stage | 5-stage | ✅ |
| **Multiply** | 32×32, 1-cycle | 1-cycle | ✅ |
| **Divide** | 2-12 cycles | 1-cycle | ✅ |
| **Interrupts** | NVIC (up to 240) | Need PLIC/CLIC | P0 |
| **MPU** | 8 regions | Need PMP | P2 |
| **Timers** | SysTick + GP timers | Need | P0 |
| **DMA** | Multi-channel | Need | P1 |
| **ADC** | Vendor (12-bit) | Need | P1 |
| **PWM** | Vendor | Need | P1 |

### Key Advantages of RISC-V/Svarog
1. **Open ISA** - No licensing fees
2. **Better pipeline** - 5 stages vs 2-3 for Cortex-M
3. **Faster divide** - 1 cycle vs 2-12 cycles
4. **Extensible** - Can add custom instructions
5. **Transparent** - Open source, fully auditable
6. **Modern tooling** - Chisel HDL, formal verification ready

---

## Roadmap Phases

### Phase 0: Foundation (Critical for Embedded) - **8-12 weeks**
Make Svarog usable for basic embedded applications.

**P0-1: Interrupt/Exception Handling** ⚠️ BLOCKING
- Implement RISC-V Machine mode interrupts
- Add PLIC (Platform-Level Interrupt Controller) or CLIC (Core-Local Interrupt Controller)
- Exception support: Illegal instruction, misaligned access, ecall/ebreak
- Trap vector table and handler mechanism
- mtvec, mepc, mcause, mstatus CSRs
- **Why**: No embedded system works without interrupts
- **Estimated effort**: 3-4 weeks
- **Testing**: Interrupt latency, nested interrupts, exception handling

**P0-2: System Timers**
- SysTick-equivalent timer (1ms tick for RTOS)
- 2-4× General Purpose Timers (GPT)
  - 32-bit up/down counters
  - Prescaler support (1-65536 divider)
  - Compare/capture modes
  - Interrupt on overflow/compare
- Watchdog timer (WDT) with reset capability
- **Why**: RTOS requires SysTick, applications need timing
- **Estimated effort**: 2-3 weeks
- **Testing**: Timer accuracy, interrupt generation, watchdog reset

**P0-3: GPIO (General Purpose I/O)**
- 32-64 configurable pins
- Per-pin configuration:
  - Direction (input/output)
  - Pull-up/pull-down resistors
  - Drive strength (2-8mA)
  - Interrupt on edge/level (rising/falling/both)
- Atomic set/clear/toggle operations
- **Why**: Essential for controlling external devices
- **Estimated effort**: 1-2 weeks
- **Testing**: Pin control, interrupt triggering, concurrent access

**P0-4: Enhanced Memory Subsystem**
- Increase TCM to 128KB-256KB (configurable)
- Add external memory interface (basic SRAM controller)
- Memory-mapped peripheral space organization
- Flash memory controller (for program storage)
- **Why**: Real applications need more RAM and non-volatile storage
- **Estimated effort**: 2 weeks
- **Testing**: Memory stress tests, flash write/erase

### Phase 1: Peripheral Expansion (Competitive Feature Set) - **10-14 weeks**
Add peripherals to compete with Cortex-M0+/M3 offerings.

**P1-1: SPI (Serial Peripheral Interface)**
- 2-3× SPI masters
- Full-duplex operation
- Configurable clock speed (1-25 MHz)
- Hardware NSS (slave select) management
- FIFO buffers (16-32 bytes TX/RX)
- DMA support
- **Use cases**: Sensors, SD cards, displays, external flash
- **Estimated effort**: 2 weeks
- **Testing**: Loopback tests, speed verification, FIFO overflow handling

**P1-2: I2C (Inter-Integrated Circuit)**
- 2× I2C masters
- Standard (100 kHz) and Fast (400 kHz) modes
- 7-bit and 10-bit addressing
- Multi-master arbitration
- Clock stretching support
- **Use cases**: Sensors (temperature, pressure, IMU), EEPROMs
- **Estimated effort**: 2 weeks
- **Testing**: Multi-device communication, clock stretching, arbitration

**P1-3: ADC (Analog-to-Digital Converter)**
- 12-bit resolution (minimum)
- 8-16 channels
- Sampling rate: 100-500 ksps
- Single/continuous/scan modes
- Interrupt on conversion complete
- DMA support for continuous sampling
- Internal temperature sensor channel
- **Use cases**: Sensor reading, battery monitoring, analog input
- **Estimated effort**: 3-4 weeks (if using soft IP; 1-2 weeks for peripheral wrapper)
- **Testing**: Accuracy tests, sampling rate, DMA integration

**P1-4: PWM (Pulse Width Modulation)**
- 6-12× PWM channels
- 16-bit resolution
- Frequency: 1 Hz - 1 MHz
- Configurable duty cycle (0-100%)
- Synchronous/phase-shifted operation
- Deadtime insertion (for motor control)
- **Use cases**: LED dimming, motor control, servo control, audio
- **Estimated effort**: 2 weeks
- **Testing**: Frequency accuracy, duty cycle linearity, synchronization

**P1-5: DMA (Direct Memory Access)**
- 4-8 DMA channels
- Memory-to-memory, memory-to-peripheral, peripheral-to-memory
- Circular buffer mode
- Priority levels per channel
- Interrupt on transfer complete/half-complete/error
- Support for UART, SPI, I2C, ADC
- **Why**: Offloads CPU for data transfers, essential for efficiency
- **Estimated effort**: 3-4 weeks
- **Testing**: Concurrent transfers, priority handling, peripheral integration

**P1-6: Power Management**
- Clock gating per peripheral
- Low-power modes:
  - Sleep (WFI instruction)
  - Deep sleep (reduced clock)
  - Standby (wake on interrupt)
- Voltage scaling (if applicable to FPGA/ASIC target)
- Wake-up source configuration
- **Why**: Critical for battery-powered IoT devices
- **Estimated effort**: 2-3 weeks
- **Testing**: Power consumption measurement, wake-up latency

**P1-7: RTC (Real-Time Clock)**
- Calendar (date/time) support
- Alarm interrupts
- Subsecond precision (1/1024 second)
- Battery backup domain (if applicable)
- Tamper detection pins
- **Use cases**: Timekeeping, scheduled wake-up, logging
- **Estimated effort**: 2 weeks
- **Testing**: Time accuracy over extended periods, alarm triggering

### Phase 2: Advanced Features (Premium Microcontroller) - **8-12 weeks**
Features for automotive, industrial, and security-critical applications.

**P2-1: Physical Memory Protection (PMP)**
- RISC-V PMP extension (8-16 regions)
- Configurable region boundaries
- Access permissions: R/W/X per region
- Lock bit to prevent reconfiguration
- Fault handling on violations
- **Why**: Memory safety for secure applications, RTOS task isolation
- **Estimated effort**: 2-3 weeks
- **Testing**: Permission enforcement, fault generation

**P2-2: Enhanced Interrupt Controller**
- CLIC (Core-Local Interrupt Controller) instead of basic PLIC
- Vectored interrupts (fast dispatch)
- Interrupt priorities (8-16 levels)
- Nested interrupt support
- Selective interrupt masking
- **Why**: Lower latency for real-time applications
- **Estimated effort**: 2-3 weeks
- **Testing**: Latency measurement, priority preemption

**P2-3: CAN Bus Controller**
- CAN 2.0B protocol
- Configurable bit rate (up to 1 Mbps)
- Message filtering (acceptance masks)
- TX/RX FIFO buffers
- Error detection and handling
- **Use cases**: Automotive, industrial automation
- **Estimated effort**: 3-4 weeks (complex protocol)
- **Testing**: CAN bus compliance, error handling

**P2-4: Security Features**
- True Random Number Generator (TRNG)
- Hardware AES accelerator (128/256-bit)
- Secure boot mechanism
- OTP (One-Time Programmable) memory for keys
- Tamper detection
- **Why**: Essential for IoT security, device authentication
- **Estimated effort**: 4-6 weeks
- **Testing**: Randomness tests (NIST), AES test vectors, boot verification

**P2-5: USB Device Controller**
- USB 2.0 Full-Speed (12 Mbps)
- 4-8 endpoints
- Control/bulk/interrupt transfers
- Ping-pong buffering
- **Use cases**: USB-to-serial, firmware updates, HID devices
- **Estimated effort**: 4-5 weeks (complex protocol)
- **Testing**: USB compliance testing, enumeration

**P2-6: Advanced Debug**
- RISC-V Debug Specification compliance
- Hardware breakpoints (4-8)
- Hardware watchpoints (2-4)
- Trace support (instruction/data trace)
- Performance counters (cycle, instruction, cache misses)
- **Why**: Professional development tools support
- **Estimated effort**: 3-4 weeks
- **Testing**: GDB integration, breakpoint accuracy

### Phase 3: Performance & Optimization (Differentiation) - **6-8 weeks**
Features that make Svarog stand out from commodity MCUs.

**P3-1: Instruction Cache**
- 2-4 KB direct-mapped or 2-way set-associative
- Reduces memory latency for instruction fetch
- Configurable in YAML
- **Why**: Improves performance for code in slower memory
- **Estimated effort**: 3-4 weeks
- **Testing**: Hit rate measurement, performance benchmarks

**P3-2: Compressed Instructions (C Extension)**
- RISC-V RV32IMC support
- 16-bit compressed instructions
- Reduces code size by 25-30%
- **Why**: More code fits in limited flash/RAM
- **Estimated effort**: 2-3 weeks
- **Testing**: Compressed code execution, mixed 16/32-bit

**P3-3: Fast Interrupts**
- Zero-latency tail-chaining
- Late-arriving interrupt preemption
- Interrupt stack frame optimization
- **Why**: Achieve <10 cycle interrupt latency (vs typical 12-16)
- **Estimated effort**: 2 weeks
- **Testing**: Latency measurement, stress testing

**P3-4: Custom Instructions**
- Framework for user-defined instructions
- Example: Bit manipulation (Zba, Zbb, Zbc)
- Example: Packed SIMD for DSP
- **Why**: Acceleration for specific applications
- **Estimated effort**: 2-3 weeks per extension
- **Testing**: Custom instruction verification

---

## Implementation Priority Matrix

### Priority Levels
- **P0 (Critical)**: Blocking - required for basic embedded use
- **P1 (High)**: Competitive - needed to match Cortex-M0+/M3
- **P2 (Medium)**: Premium - for advanced/industrial applications
- **P3 (Low)**: Differentiation - stand-out features

### Dependency Graph
```
Phase 0 (Foundation)
├─ P0-1: Interrupts ← BLOCKS ALL PERIPHERALS
│   ├─ P0-2: Timers (needs interrupt support)
│   ├─ P0-3: GPIO (needs interrupt support)
│   └─ P0-4: Memory (independent)
│
Phase 1 (Peripherals) - depends on Phase 0
├─ P1-1: SPI ← needs DMA, interrupts
├─ P1-2: I2C ← needs interrupts
├─ P1-3: ADC ← needs DMA, interrupts
├─ P1-4: PWM ← needs timers
├─ P1-5: DMA ← needs interrupt controller
├─ P1-6: Power Mgmt ← needs interrupt for wake-up
└─ P1-7: RTC ← needs interrupts

Phase 2 (Advanced) - depends on Phase 0-1
├─ P2-1: PMP (independent)
├─ P2-2: CLIC ← replaces basic PLIC from P0-1
├─ P2-3: CAN ← needs DMA, interrupts
├─ P2-4: Security (independent)
├─ P2-5: USB ← needs DMA, interrupts
└─ P2-6: Debug (independent)

Phase 3 (Performance) - depends on stable base
├─ P3-1: I-Cache (independent)
├─ P3-2: Compressed ISA (independent)
├─ P3-3: Fast Interrupts ← replaces P2-2
└─ P3-4: Custom Instructions (independent)
```

---

## Technical Specifications

### M Extension Rework (P0-0) ⚠️ CRITICAL FIX

**Current Implementation Issues**:

Location: `src/main/scala/svarog/bits/Multipliers.scala`, `Dividers.scala`

**Problem**:
```scala
// Current SimpleMultiplier (lines 39, 44, 49, 54)
val fullMul = multiplicant * multiplier  // Combinational 32×32 multiply!
io.result.bits := fullMul(xlen - 1, 0)

// Current SimpleDivider (lines 48, 58, 73, 83)
val quotient = dividend.asSInt / divisor.asSInt  // Combinational divide!
io.result.bits := quotient.asUInt
```

These synthesize to huge combinational paths that cannot meet 50 MHz timing.

**Solution: Pipelined/Iterative Implementation**

**Multiplier (3 options)**:

1. **DSP Block Instantiation** (Recommended for Xilinx)
   ```scala
   // Use Xilinx DSP48E1 primitive
   // Latency: 1-3 cycles (configurable)
   // Resources: 2-4 DSP blocks
   // Throughput: 1 multiply per cycle (pipelined)
   ```

2. **Booth Encoding + Wallace Tree** (Generic)
   ```scala
   // 3-stage pipeline:
   // Stage 1: Booth encoding
   // Stage 2: Partial product reduction (Wallace tree)
   // Stage 3: Final carry propagate adder
   // Latency: 3 cycles
   // Resources: ~600-800 LUTs
   ```

3. **Iterative Multiplier** (Smallest area)
   ```scala
   // Shift-and-add algorithm
   // Latency: 32 cycles (one per bit)
   // Resources: ~200-300 LUTs
   // Not recommended for performance
   ```

**Divider (2 options)**:

1. **Non-Restoring Divider** (Recommended)
   ```scala
   // Iterative algorithm
   // Latency: 32-34 cycles
   // Resources: ~400-600 LUTs
   // Similar to Cortex-M3 (2-12 cycles)
   // RISC-V allows variable latency
   ```

2. **Radix-4 SRT Divider** (Faster but larger)
   ```scala
   // Latency: 16-18 cycles
   // Resources: ~800-1200 LUTs
   // Better performance, more complex
   ```

**Recommended Configuration**:
- **Multiplier**: DSP blocks (on Xilinx/Altera) or 3-stage pipelined
- **Divider**: Non-restoring iterative (32-34 cycles)
- **Interface**: Keep existing Decoupled interface, add `ready` signal
- **Impact**: Execute stage may stall waiting for mult/div completion

**Modified Interface**:
```scala
class AbstractMultiplier(xlen: Int) extends Module {
  val io = IO(new Bundle {
    val inp = Flipped(Decoupled(new MultiplierIO(xlen)))
    val result = Decoupled(UInt(xlen.W))  // Changed: now has ready signal
  })
}
```

**Integration Changes**:
- Execute stage: Add stall logic for multi-cycle multiply/divide
- Hazard unit: Detect multiply/divide in progress, stall pipeline
- Testing: Verify all M extension tests still pass with new timing

**Estimated Effort**: 2-3 days
**Priority**: Must complete before other Phase 0 work (timing is critical)

---

### Interrupt Controller (P0-1)

**Architecture Choice**: Start with PLIC, migrate to CLIC in Phase 2

**PLIC (Platform-Level Interrupt Controller)**
```
Base Address: 0x0C000000
Registers:
├─ 0x000000: Priority[1-127]        - Interrupt priorities (0-7)
├─ 0x001000: Pending[1-127]         - Pending interrupt bits
├─ 0x002000: Enable[0-31]           - Enable bits per context
├─ 0x200000: Threshold              - Priority threshold
├─ 0x200004: Claim/Complete         - Claim and completion

Configuration:
- 32-64 external interrupt sources
- 8 priority levels (0 = disabled, 7 = highest)
- Gateway per interrupt (edge/level detection)
- Context support for multiple cores (future)
```

**CSR Extensions**
```
Machine Mode CSRs:
├─ mtvec    (0x305): Trap vector base address
├─ mstatus  (0x300): Machine status (MIE, MPIE)
├─ mie      (0x304): Machine interrupt enable
├─ mip      (0x344): Machine interrupt pending
├─ mepc     (0x341): Machine exception PC
├─ mcause   (0x342): Machine trap cause
├─ mtval    (0x343): Machine trap value
└─ mscratch (0x340): Machine scratch register
```

**Trap Causes**
```
Exceptions:
├─ 0: Instruction address misaligned
├─ 1: Instruction access fault
├─ 2: Illegal instruction
├─ 3: Breakpoint (EBREAK)
├─ 4: Load address misaligned
├─ 5: Load access fault
├─ 6: Store address misaligned
├─ 7: Store access fault
└─ 11: Environment call (ECALL)

Interrupts (mcause[31] = 1):
├─ 3: Machine software interrupt
├─ 7: Machine timer interrupt
└─ 11: Machine external interrupt
```

### Timer Subsystem (P0-2)

**System Timer (SysTick)**
```
Base Address: 0x0E000000
Registers:
├─ 0x00: CTRL      - Control and status
│   ├─ [0]: ENABLE
│   ├─ [1]: TICKINT (interrupt enable)
│   └─ [16]: COUNTFLAG (overflow flag)
├─ 0x04: LOAD      - Reload value (24-bit)
├─ 0x08: VAL       - Current value (24-bit)
└─ 0x0C: CALIB     - Calibration value

Configuration:
- 24-bit down-counter
- Reload on underflow
- Interrupt on zero
- Clock source: CPU clock or CPU clock / 8
```

**General Purpose Timers (4× instances)**
```
Base Address: 0x40000000 (TIM1), 0x40000400 (TIM2), etc.
Registers per timer:
├─ 0x00: CR1       - Control register 1
│   ├─ [0]: CEN (counter enable)
│   ├─ [1]: UDIS (update disable)
│   ├─ [2]: URS (update request source)
│   ├─ [3]: OPM (one-pulse mode)
│   ├─ [4]: DIR (direction: 0=up, 1=down)
│   └─ [7]: ARPE (auto-reload preload enable)
├─ 0x04: CR2       - Control register 2
├─ 0x08: DIER      - DMA/interrupt enable
│   ├─ [0]: UIE (update interrupt enable)
│   └─ [1-4]: CCxIE (capture/compare interrupt enable)
├─ 0x0C: SR        - Status register
├─ 0x10: CNT       - Counter (32-bit)
├─ 0x14: PSC       - Prescaler (16-bit, divide by PSC+1)
├─ 0x18: ARR       - Auto-reload register (32-bit)
├─ 0x34-0x40: CCR1-CCR4 - Capture/compare registers
└─ 0x44: BDTR      - Break and dead-time register

Features per timer:
- 32-bit up/down/up-down counter
- 4× capture/compare channels
- PWM output capability
- Input capture with prescaler
- One-pulse mode
- Encoder interface (quadrature decoder)
- DMA request generation
```

**Watchdog Timer**
```
Base Address: 0x40003000
Registers:
├─ 0x00: KR        - Key register (0xAAAA refresh, 0xCCCC start)
├─ 0x04: PR        - Prescaler (divide LSI clock)
├─ 0x08: RLR       - Reload register (12-bit)
└─ 0x0C: SR        - Status register

Configuration:
- 12-bit down-counter
- Timeout: 0.1ms to 32s (with 32 kHz clock)
- Generates system reset on timeout
- Window mode: refresh only in time window
```

### GPIO (P0-3)

**GPIO Ports (4-8 ports, 16 pins each = 64-128 total pins)**
```
Base Address: 0x48000000 (PORTA), 0x48000400 (PORTB), etc.
Registers per port:
├─ 0x00: MODER     - Mode register (2 bits per pin)
│   └─ 00=input, 01=output, 10=alternate, 11=analog
├─ 0x04: OTYPER    - Output type (0=push-pull, 1=open-drain)
├─ 0x08: OSPEEDR   - Output speed (00=low, 11=high)
├─ 0x0C: PUPDR     - Pull-up/pull-down (00=none, 01=PU, 10=PD)
├─ 0x10: IDR       - Input data register (read-only)
├─ 0x14: ODR       - Output data register (read/write)
├─ 0x18: BSRR      - Bit set/reset register (atomic)
│   ├─ [15:0]: Set bits (write 1 to set)
│   └─ [31:16]: Reset bits (write 1 to reset)
├─ 0x1C: LCKR      - Configuration lock register
├─ 0x20: AFRL      - Alternate function low (pins 0-7)
└─ 0x24: AFRH      - Alternate function high (pins 8-15)

Interrupt Controller (per port):
├─ 0x40: IMR       - Interrupt mask register
├─ 0x44: EMR       - Event mask register
├─ 0x48: RTSR      - Rising trigger selection
├─ 0x4C: FTSR      - Falling trigger selection
├─ 0x50: SWIER     - Software interrupt event
└─ 0x54: PR        - Pending register (write 1 to clear)

Features:
- Per-pin configuration
- Atomic set/clear/toggle operations
- Interrupt on edge/level (16 external interrupt lines)
- Configurable drive strength
- Schmitt trigger inputs
```

### SPI (P1-1)

**SPI Master (2-3× instances)**
```
Base Address: 0x40013000 (SPI1), 0x40003800 (SPI2), etc.
Registers:
├─ 0x00: CR1       - Control register 1
│   ├─ [0]: CPHA (clock phase)
│   ├─ [1]: CPOL (clock polarity)
│   ├─ [2]: MSTR (master mode)
│   ├─ [3-5]: BR (baud rate: f_PCLK / 2^(BR+1))
│   ├─ [6]: SPE (SPI enable)
│   ├─ [7]: LSBFIRST (frame format)
│   ├─ [8]: SSI (internal slave select)
│   ├─ [9]: SSM (software slave management)
│   └─ [11]: DFF (data frame format: 0=8-bit, 1=16-bit)
├─ 0x04: CR2       - Control register 2
│   ├─ [0]: RXDMAEN (RX DMA enable)
│   ├─ [1]: TXDMAEN (TX DMA enable)
│   ├─ [2]: SSOE (SS output enable)
│   └─ [5-7]: Interrupt enables
├─ 0x08: SR        - Status register
│   ├─ [0]: RXNE (RX buffer not empty)
│   ├─ [1]: TXE (TX buffer empty)
│   ├─ [7]: BSY (busy flag)
│   └─ Error flags (OVR, MODF, etc.)
├─ 0x0C: DR        - Data register (8/16-bit)
└─ 0x1C: CRCPR     - CRC polynomial register

FIFO Configuration:
- TX FIFO: 16-32 bytes
- RX FIFO: 16-32 bytes
- Threshold interrupt triggers

Clock Speeds:
- f_PCLK / 2, 4, 8, 16, 32, 64, 128, 256
- Maximum: 25 MHz (for 50 MHz PCLK)
```

### I2C (P1-2)

**I2C Master (2× instances)**
```
Base Address: 0x40005400 (I2C1), 0x40005800 (I2C2)
Registers:
├─ 0x00: CR1       - Control register 1
│   ├─ [0]: PE (peripheral enable)
│   ├─ [1-7]: Interrupt enables
│   └─ [15]: SWRST (software reset)
├─ 0x04: CR2       - Control register 2
│   ├─ [0-5]: FREQ (peripheral clock frequency in MHz)
│   ├─ [11]: DMAEN (DMA enable)
│   └─ [12]: LAST (DMA last transfer)
├─ 0x08: OAR1      - Own address register 1 (slave mode)
├─ 0x0C: OAR2      - Own address register 2 (dual addressing)
├─ 0x10: DR        - Data register
├─ 0x14: SR1       - Status register 1
│   ├─ [0]: SB (start bit)
│   ├─ [1]: ADDR (address sent/matched)
│   ├─ [2]: BTF (byte transfer finished)
│   ├─ [6]: RXNE (data register not empty)
│   └─ [7]: TXE (data register empty)
├─ 0x18: SR2       - Status register 2
│   ├─ [0]: MSL (master/slave)
│   ├─ [1]: BUSY
│   └─ [2]: TRA (transmitter/receiver)
└─ 0x1C: CCR       - Clock control register
    ├─ [0-11]: CCR (clock control)
    └─ [15]: F/S (I2C mode: 0=standard, 1=fast)

Clock Speeds:
- Standard mode: 100 kHz (T_high = T_low = 5 µs)
- Fast mode: 400 kHz (T_high = 1.25 µs, T_low = 3.75 µs)
- Clock stretching supported

Features:
- 7-bit and 10-bit addressing
- Multi-master with arbitration
- Slave mode (optional)
- General call addressing
- SMBus support (optional)
```

### ADC (P1-3)

**ADC Controller (12-bit)**
```
Base Address: 0x40012400
Registers:
├─ 0x00: SR        - Status register
│   ├─ [0]: AWD (analog watchdog flag)
│   ├─ [1]: EOC (end of conversion)
│   └─ [2]: JEOC (injected channel end of conversion)
├─ 0x04: CR1       - Control register 1
│   ├─ [0-4]: Channel selection
│   ├─ [5]: EOCIE (EOC interrupt enable)
│   ├─ [8]: SCAN (scan mode enable)
│   └─ [23]: AWDEN (analog watchdog enable on regular channels)
├─ 0x08: CR2       - Control register 2
│   ├─ [0]: ADON (ADC on)
│   ├─ [1]: CONT (continuous conversion)
│   ├─ [8]: DMA (DMA enable)
│   ├─ [11]: ALIGN (data alignment: 0=right, 1=left)
│   └─ [20-22]: EXTEN (external trigger enable)
├─ 0x0C: SMPR1     - Sample time register 1 (channels 10-17)
├─ 0x10: SMPR2     - Sample time register 2 (channels 0-9)
├─ 0x28: SQR1      - Regular sequence register 1 (length + channels 13-16)
├─ 0x2C: SQR2      - Regular sequence register 2 (channels 7-12)
├─ 0x30: SQR3      - Regular sequence register 3 (channels 1-6)
├─ 0x4C: DR        - Regular data register (12-bit result)
└─ 0xB0: CCR       - Common control register

Channel Configuration:
- 16 external channels
- 2 internal channels (temperature sensor, VREF)
- Sample time: 3, 15, 28, 56, 84, 112, 144, 480 cycles
- Conversion time: Sample time + 12 cycles

Modes:
- Single conversion
- Continuous conversion
- Scan mode (multiple channels)
- Discontinuous mode
- Injected channels (priority conversions)

Resolution: 12-bit (0-4095)
Sampling Rate: 100-500 ksps (depends on clock and sample time)
```

### PWM (P1-4)

**PWM Channels (6-12 channels, using GP timers)**
```
Leverage TIM1-TIM4 for PWM generation (see P0-2)
Each timer provides 4 PWM channels

Configuration per channel:
├─ TIMx_CCMRy[6:4]: OCxM (output compare mode)
│   └─ 110 = PWM mode 1, 111 = PWM mode 2
├─ TIMx_CCMRy[3]: OCxPE (output compare preload enable)
├─ TIMx_CCER[0]: CCxE (capture/compare output enable)
├─ TIMx_CCER[1]: CCxP (output polarity)
├─ TIMx_CCRx: Compare value (duty cycle)
└─ TIMx_ARR: Period (frequency)

PWM Frequency = f_CLK / ((PSC + 1) × (ARR + 1))
Duty Cycle = (CCRx / ARR) × 100%

Example (50 MHz clock):
- 1 kHz PWM: PSC=49, ARR=999 → 50MHz / (50 × 1000) = 1 kHz
- 16-bit resolution at 763 Hz: PSC=0, ARR=65535
- 10-bit resolution at 48.8 kHz: PSC=0, ARR=1023

Features:
- Independent duty cycle per channel
- Complementary outputs (for H-bridge)
- Dead-time insertion (prevent shoot-through)
- Phase-shifted PWM (multi-channel synchronization)
- Center-aligned mode (reduces EMI)
```

### DMA (P1-5)

**DMA Controller (8 channels)**
```
Base Address: 0x40020000
Registers per channel:
├─ 0x08 + n×0x14: CCRn   - Channel configuration
│   ├─ [0]: EN (channel enable)
│   ├─ [1]: TCIE (transfer complete interrupt enable)
│   ├─ [2]: HTIE (half-transfer interrupt enable)
│   ├─ [3]: TEIE (transfer error interrupt enable)
│   ├─ [4]: DIR (data transfer direction: 0=periph→mem, 1=mem→periph)
│   ├─ [5]: CIRC (circular mode)
│   ├─ [6]: PINC (peripheral increment)
│   ├─ [7]: MINC (memory increment)
│   ├─ [8-9]: PSIZE (peripheral data size: 00=8bit, 01=16bit, 10=32bit)
│   ├─ [10-11]: MSIZE (memory data size)
│   └─ [12-13]: PL (priority level: 00=low, 11=very high)
├─ 0x0C + n×0x14: CNDTRn  - Channel data count (16-bit)
├─ 0x10 + n×0x14: CPARn   - Channel peripheral address
└─ 0x14 + n×0x14: CMARn   - Channel memory address

Global Registers:
├─ 0x00: ISR       - Interrupt status register
│   └─ Per channel: [0]=GIF, [1]=TCIF, [2]=HTIF, [3]=TEIF
└─ 0x04: IFCR      - Interrupt flag clear register

DMA Request Mapping:
├─ CH1: ADC, TIM2_CH3, TIM4_CH1
├─ CH2: SPI1_RX, UART3_TX, TIM1_CH1
├─ CH3: SPI1_TX, UART3_RX, TIM1_CH2
├─ CH4: SPI2_RX, UART1_TX, TIM1_CH4
├─ CH5: SPI2_TX, UART1_RX, TIM1_UP
├─ CH6: I2C1_TX, TIM1_CH3, TIM3_CH1
├─ CH7: I2C1_RX, TIM2_UP, TIM4_UP
└─ CH8: TIM2_CH1, TIM3_CH2, TIM4_CH2

Transfer Modes:
- Normal mode (single block transfer)
- Circular mode (continuous loop)
- Double-buffer mode (ping-pong)

Features:
- Programmable transfer size (byte, half-word, word)
- Burst transfers (efficient bus usage)
- Priority arbitration (4 levels)
- Transfer complete/half-complete/error interrupts
```

### Memory Map (Phase 0 Complete)

```
0x00000000 - 0x0007FFFF : Flash (512 KB) [New]
0x00480000 - 0x0048FFFF : ROM Bootloader (64 KB) [Existing]
0x08000000 - 0x0803FFFF : External Flash/QSPI (256 KB) [New]

0x20000000 - 0x2003FFFF : SRAM (256 KB) [Expanded from 64KB]
0x80000000 - 0x8003FFFF : TCM (256 KB) [Expanded from 64KB]

0x40000000 - 0x400003FF : TIM1 (GP Timer 1) [New]
0x40000400 - 0x400007FF : TIM2 (GP Timer 2) [New]
0x40000800 - 0x40000BFF : TIM3 (GP Timer 3) [New]
0x40000C00 - 0x40000FFF : TIM4 (GP Timer 4) [New]
0x40002000 - 0x400023FF : RTC [New]
0x40003000 - 0x400033FF : WWDG (Watchdog) [New]
0x40003800 - 0x40003BFF : SPI2 [New]
0x40004400 - 0x400047FF : UART2 [Existing, relocated]
0x40005400 - 0x400057FF : I2C1 [New]
0x40005800 - 0x40005BFF : I2C2 [New]
0x40010000 - 0x400103FF : UART0 [Existing, relocated]
0x40011000 - 0x400113FF : UART1 [Existing, relocated]
0x40012400 - 0x400127FF : ADC1 [New]
0x40013000 - 0x400133FF : SPI1 [New]
0x40020000 - 0x400203FF : DMA1 [New]

0x48000000 - 0x480003FF : GPIOA [New]
0x48000400 - 0x480007FF : GPIOB [New]
0x48000800 - 0x48000BFF : GPIOC [New]
0x48000C00 - 0x48000FFF : GPIOD [New]

0x0C000000 - 0x0C3FFFFF : PLIC (Interrupt Controller) [New]
0x0E000000 - 0x0E00FFFF : SysTick Timer [New]

0xE0000000 - 0xE0000FFF : Debug Unit [Existing]
0xF0000000 - 0xFFFFFFFF : System Control/Config [New]
```

---

## Testing Strategy

### Phase 0 Testing

**Interrupt System**
- Unit tests: CSR read/write, trap vector calculation
- Integration tests: External interrupts, exceptions, nested interrupts
- Latency measurement: Interrupt-to-handler timing
- Stress tests: High-frequency interrupts, simultaneous interrupts

**Timers**
- Unit tests: Counter overflow, prescaler, compare match
- Integration tests: Timer-triggered interrupts, PWM output
- Accuracy tests: Long-term timing accuracy
- Stress tests: All timers running simultaneously

**GPIO**
- Unit tests: Pin configuration, read/write operations
- Integration tests: Interrupt triggering, debouncing
- Stress tests: Concurrent access, rapid toggling

### Phase 1 Testing

**SPI/I2C**
- Loopback tests: Master-slave communication
- Device tests: Real sensor/EEPROM communication
- Error tests: Bus errors, clock stretching, arbitration
- Performance tests: Maximum throughput

**ADC**
- Accuracy tests: Known voltage inputs, linearity
- Sampling rate tests: Maximum sustainable rate
- Stress tests: DMA transfers, continuous conversion

**DMA**
- Transfer tests: Memory-to-memory, peripheral-to-memory
- Priority tests: Multiple simultaneous transfers
- Error tests: Invalid addresses, alignment

**Power Management**
- Sleep mode tests: Wake-up latency, power consumption
- Clock gating tests: Peripheral enable/disable
- Integration tests: Sleep with wake-on-interrupt

### Validation Benchmarks

**CoreMark** (CPU performance)
- Target: 1.5-2.0 CoreMark/MHz (competitive with Cortex-M0+/M3)
- Measures: Integer performance, control flow

**EEMBC ULPBench** (Low-power performance)
- Target: Score comparable to Cortex-M0+ class
- Measures: Active power, sleep power, wake-up time

**EEMBC IoTMark** (IoT workloads)
- Target: Competitive with 50 MHz Cortex-M3
- Measures: Sensor processing, ML inference, connectivity

**Dhrystone** (Integer performance)
- Target: 0.9-1.1 DMIPS/MHz
- Measures: Typical embedded workload

**Interrupt Latency**
- Target: <10 cycles (Phase 3 fast interrupts)
- Baseline: <16 cycles (Phase 0 PLIC)
- Measure: Cycles from interrupt assertion to first handler instruction

---

## Resource Estimation

### FPGA Resource Requirements (Xilinx 7-Series)

**Measured Baseline** (Artix-7):
- **RV32I + Zicsr + 2×UART + Wishbone + ROM + Debug** (M extension disabled): **~5000 LUTs**

**M Extension Status**: ⚠️ Current implementation has timing failures
- Issue: Combinational 32×32 multiply/divide (lines too long for 50 MHz)
- Solution needed: Pipelined multiplier (2-3 cycles) or DSP block instantiation
- Estimated rework: +500-800 LUTs with 2-4 DSP blocks when properly implemented

| Component | LUTs | FFs | BRAM | DSP | Notes |
|-----------|------|-----|------|-----|-------|
| **Current (RV32I_Zicsr, no M)** | 5000 | ~3000 | 32 | 0 | Measured ✅ |
| + M Extension (reworked) | +700 | +400 | 0 | 2-4 | Needs pipeline fix |
| + Phase 0 (Int + Timers + GPIO + Mem) | +2800 | +2000 | +16 | 0 | +56% LUT |
| + Phase 1 (SPI + I2C + DMA + PM) | +3500 | +2500 | +24 | 0 | +70% LUT |
| + Phase 2 (Security + CAN + PMP) | +4500 | +3000 | +32 | 1-2 | +90% LUT |
| + Phase 3 (I-Cache + RV32C) | +2500 | +1500 | +64 | 0 | +50% LUT |
| **Total (Full System)** | ~19000 | ~12400 | ~168 | 3-6 | 3.8x growth |

**Breakdown by Phase** (cumulative):

| After Phase | Total LUTs | Total FFs | BRAM | Target FPGA |
|-------------|------------|-----------|------|-------------|
| **Current** | 5000 | 3000 | 32 | XC7A35T ✅ |
| **+ M fix** | 5700 | 3400 | 32 | XC7A35T ✅ |
| **Phase 0** | 8500 | 5400 | 48 | XC7A50T |
| **Phase 1** | 12000 | 7900 | 72 | XC7A75T |
| **Phase 2** | 16500 | 10900 | 104 | **XC7A100T** |
| **Phase 3** | 19000 | 12400 | 168 | **XC7A100T** |

**Target FPGAs**:
- **Current development**: Artix-7 XC7A35T/50T (sufficient for base + Phase 0)
- **Recommended for Phase 1+**: Artix-7 XC7A100T (101K LUTs, 126K FFs, 240 BRAM, 240 DSP)
  - Provides headroom: ~80% LUT utilization at Phase 3
  - Allows for optimization iterations
  - Cost-effective for development
- **Production**: Can optimize down to XC7A75T if needed

**Resource Details by Component**:

| Component | LUTs | Reasoning |
|-----------|------|-----------|
| **PLIC (32 sources)** | 1000-1500 | Priority logic, gateways, claim/complete |
| **Timers (6 total)** | 800-1200 | 4× GP timers (32-bit counters, compare), SysTick, WDT |
| **GPIO (64 pins)** | 500-800 | Configuration registers, interrupt logic per pin |
| **SPI (2× masters)** | 400-600 | FIFOs (32 bytes each), state machines |
| **I2C (2× masters)** | 400-600 | Bit-level control, clock stretching, arbitration |
| **DMA (8 channels)** | 1500-2000 | Arbitration, address generators, channel state |
| **Power Mgmt** | 300-500 | Clock gating, mode control |
| **RTC** | 200-400 | Calendar logic, alarm comparators |
| **PMP (8 regions)** | 600-900 | Address comparison, permission checks |
| **CLIC** | 1200-1800 | Vectored interrupts, priority preemption |
| **CAN Controller** | 1500-2000 | Protocol state machine, bit timing, filtering |
| **AES-128** | 1500-2500 | Encryption/decryption rounds |
| **TRNG** | 500-800 | Entropy source, conditioning |
| **I-Cache (4KB)** | 1500-2000 | Tag memory, valid bits, hit logic |
| **RV32C Decoder** | 800-1200 | Decompression logic, dual-width fetch |

### ASIC Estimation (for reference)

| Metric | Phase 0 | Phase 1 | Phase 2 | Phase 3 |
|--------|---------|---------|---------|---------|
| Gate Count | ~35K | ~60K | ~90K | ~105K |
| SRAM (KB) | 320 | 512 | 640 | 768 |
| Area (mm², 28nm) | ~0.3 | ~0.5 | ~0.7 | ~0.8 |
| Power (mW @ 50MHz, 28nm) | ~2 | ~3.5 | ~5 | ~5.5 |

**Note**: These are rough estimates for planning. Actual numbers depend on synthesis tools, technology node, and optimizations.

---

## Development Timeline

**Historical Development Cadence** (from commit history):
- Project start: July 26, 2025
- Current state (Dec 22, 2025): ~5 months elapsed
- Major features implemented: Pipeline, M extension, Zicsr, Wishbone, UART, ROM bootloader
- **Observed pattern**: Features take 1-3 days of focused work, with variable gaps between sessions
- **Typical throughput**: 1-2 features per week when actively developing

**Timeline Estimates** (based on actual development pace):

### Phase 0: Foundation
**Estimated: 6-10 weeks of active development** (calendar time may vary based on schedule)

```
M Extension Rework    : 2-3 days
  - Replace combinational multiply/divide with pipelined version
  - Use DSP blocks for multiplier
  - Iterative divider (32-cycle worst case)
  - CRITICAL: Fixes timing failures at 50 MHz

Interrupt/Exception   : 5-7 days (P0-1)
  - CSR extensions (mtvec, mepc, mcause, mstatus, etc.)
  - Trap entry/exit logic in pipeline
  - PLIC implementation (32 sources, 8 priorities)
  - Exception handlers (misaligned, illegal inst, ecall, ebreak)
  - Testing: Interrupt latency, nested interrupts

System Timers        : 4-6 days (P0-2)
  - SysTick (24-bit, 1ms tick)
  - 4× General Purpose Timers (32-bit, capture/compare, PWM mode)
  - Watchdog timer (system reset on timeout)
  - Testing: Accuracy, interrupt generation

GPIO                 : 2-3 days (P0-3)
  - 64 pins across 4 ports
  - Per-pin: direction, pull resistors, drive strength
  - Edge/level interrupt support
  - Atomic set/clear/toggle

Memory Expansion     : 2-3 days (P0-4)
  - Expand TCM to 256KB (configuration)
  - Flash controller (basic SPI flash interface)
  - Memory map reorganization

Testing & Debug      : 3-5 days
  - RTOS bring-up (FreeRTOS or Zephyr)
  - CoreMark baseline
  - Interrupt stress tests

Milestone: Basic embedded app runs (RTOS tasks, timer interrupts, GPIO control)
```

### Phase 1: Peripherals
**Estimated: 8-12 weeks of active development**

```
SPI                  : 2-3 days (P1-1)
  - 2× SPI masters, 25 MHz clock
  - TX/RX FIFOs (16-32 bytes)
  - Hardware NSS management
  - Testing: Loopback, external flash communication

I2C                  : 2-3 days (P1-2)
  - 2× I2C masters (100/400 kHz)
  - Clock stretching, multi-master arbitration
  - Testing: Sensor communication (I2C temp/humidity sensor)

DMA                  : 5-8 days (P1-5)
  - 8 channels, priority arbitration
  - Memory↔memory, memory↔peripheral
  - Circular mode, interrupt on complete
  - Integration with UART, SPI, I2C, ADC
  - Testing: Concurrent transfers, priority handling

PWM                  : 1-2 days (P1-4)
  - Leverage GP timers from Phase 0
  - 12 channels (3 per timer × 4 timers)
  - Deadtime insertion for motor control
  - Testing: Frequency/duty cycle accuracy

ADC                  : 4-6 days (P1-3) *or use external ADC via SPI
  - Option A: Integrate IP core (12-bit, 8-16 channels)
  - Option B: External ADC via SPI (faster integration)
  - DMA support for continuous sampling
  - Testing: Accuracy, sampling rate

Power Management     : 2-3 days (P1-6)
  - Clock gating per peripheral
  - WFI sleep modes (sleep, deep sleep, standby)
  - Wake-on-interrupt configuration
  - Testing: Power measurement, wake latency

RTC                  : 2-3 days (P1-7)
  - Calendar (date/time), subsecond precision
  - Alarm interrupts, tamper detection
  - Testing: Long-term accuracy

Integration & Testing: 4-6 days
  - IoT demo: Sensor → UART/SPI/I2C communication
  - DMA stress testing
  - Power mode transitions
  - CoreMark with peripherals active

Milestone: IoT sensor node application (sensor reads, DMA transfers, low-power modes)
```

### Phase 2: Advanced Features
**Estimated: 7-10 weeks of active development**

```
PMP                  : 3-4 days (P2-1)
  - 8-16 regions, R/W/X permissions
  - Lock bits, fault handling
  - Testing: Memory protection enforcement

CLIC                 : 4-6 days (P2-2)
  - Vectored interrupts, fast dispatch
  - 8-16 priority levels, preemption
  - Replace basic PLIC from Phase 0
  - Testing: Latency measurement (<10 cycles target)

CAN Controller       : 6-8 days (P2-3)
  - CAN 2.0B protocol (1 Mbps)
  - Message filtering, TX/RX FIFOs
  - Error detection/handling
  - Testing: CAN bus compliance, automotive ECU communication

Security (TRNG)      : 2-3 days (P2-4a)
  - True random number generator
  - Entropy conditioning
  - Testing: NIST randomness tests

Security (AES)       : 4-5 days (P2-4b)
  - AES-128/256 accelerator (use open IP like OpenTitan)
  - ECB/CBC modes
  - Testing: NIST test vectors

Secure Boot          : 3-4 days (P2-4c)
  - Boot ROM signature verification
  - OTP key storage
  - Testing: Boot integrity

Enhanced Debug       : 3-4 days (P2-6)
  - Hardware breakpoints (4-8)
  - Hardware watchpoints (2-4)
  - Performance counters
  - Testing: GDB integration

Integration & Testing: 5-7 days
  - Automotive demo (CAN + secure boot)
  - Security testing (boot verification, AES test vectors)

Milestone: Automotive/industrial-grade features operational
```

### Phase 3: Performance
**Estimated: 4-6 weeks of active development**

```
Instruction Cache    : 5-7 days (P3-1)
  - 2-4 KB, direct-mapped or 2-way associative
  - Cache controller, hit/miss logic
  - Testing: Hit rate measurement, performance improvement

Compressed ISA (C)   : 4-5 days (P3-2)
  - RV32C decoder (16-bit instruction expansion)
  - Mixed 16/32-bit fetch logic
  - Testing: Code size reduction, execution correctness

Fast Interrupts      : 3-4 days (P3-3)
  - Tail-chaining (zero-latency between interrupts)
  - Late-arriving preemption
  - Stack frame optimization
  - Testing: Latency <10 cycles

Optimization         : 3-5 days
  - Timing closure (ensure 50 MHz across all paths)
  - Area optimization (reduce LUT count if needed)
  - Power optimization

Benchmarking         : 2-3 days
  - CoreMark, Dhrystone
  - EEMBC ULPBench, IoTMark
  - Comparative analysis vs Cortex-M3

Milestone: Performance competitive with Cortex-M3, benchmarks published
```

**Total Active Development Time**: 25-38 weeks (~6-9 months of focused work)
**Realistic Calendar Time**: 8-14 months (accounting for gaps, parallel activities, unexpected issues)

**Note on Timeline Variability**:
- Estimates assume 1 developer working part-time (based on historical commit pattern)
- Can be accelerated with:
  - Full-time focused development
  - Using existing open-source IP cores (OpenCores, OpenTitan)
  - Parallelizing independent features (e.g., SPI + I2C simultaneously)
- May be extended by:
  - Timing closure challenges (especially for Phase 3)
  - Integration issues between components
  - Extensive testing and validation requirements

---

## Success Criteria

### Phase 0 Complete
- [ ] External interrupts work with <16 cycle latency
- [ ] All exception types handled correctly
- [ ] Timers generate accurate interrupts (±1% accuracy over 1 second)
- [ ] GPIO pins controllable and interrupt-capable
- [ ] FreeRTOS or Zephyr RTOS boots and runs tasks
- [ ] CoreMark runs successfully

### Phase 1 Complete
- [ ] SPI communicates with external flash at 25 MHz
- [ ] I2C reads from temperature/humidity sensor
- [ ] ADC samples at 100+ ksps with <1% error
- [ ] PWM generates precise waveforms (1 kHz - 100 kHz)
- [ ] DMA transfers data without CPU intervention
- [ ] Sleep modes reduce power consumption by >50%
- [ ] IoT demo application runs (sensor → cloud)
- [ ] CoreMark score >1.5 CoreMark/MHz

### Phase 2 Complete
- [ ] PMP prevents unauthorized memory access
- [ ] CAN bus communicates with automotive ECU
- [ ] TRNG passes NIST randomness tests
- [ ] AES encryption/decryption functional
- [ ] Secure boot verifies firmware signature
- [ ] Debug tools (GDB) integrate seamlessly

### Phase 3 Complete
- [ ] I-Cache improves performance by 20-30%
- [ ] Compressed code reduces binary size by 25-30%
- [ ] Interrupt latency <10 cycles
- [ ] CoreMark score >2.0 CoreMark/MHz
- [ ] EEMBC benchmarks competitive with Cortex-M3
- [ ] Custom instruction examples functional

---

## Competitive Positioning

### After Phase 0 (Foundation)
**Competitive with**: ARM Cortex-M0 (minimal embedded MCU)
- Basic interrupt handling
- Timer and GPIO
- Suitable for: Simple control applications, learning platforms

### After Phase 1 (Peripherals)
**Competitive with**: ARM Cortex-M0+ / M3 (mainstream embedded MCU)
- Rich peripheral set
- DMA and power management
- Suitable for: IoT devices, smart home, industrial sensors, wearables

**Market segments**:
- Smart home devices (sensors, actuators)
- Wearable devices (fitness trackers, smartwatches)
- Industrial I/O modules
- Battery-powered sensors
- Educational platforms

### After Phase 2 (Advanced)
**Competitive with**: ARM Cortex-M3/M4 (premium embedded MCU)
- Security features
- Automotive-grade peripherals (CAN)
- Memory protection
- Suitable for: Automotive, industrial automation, secure IoT

**Market segments**:
- Automotive body control modules
- Industrial PLCs
- Secure IoT gateways
- Medical devices
- Drone flight controllers

### After Phase 3 (Performance)
**Differentiators vs Cortex-M**:
- **Open source**: No licensing fees, full auditability
- **Faster divide**: 1-cycle vs 2-12 cycles (Cortex-M3)
- **Better pipeline**: 5-stage vs 3-stage (Cortex-M3)
- **Extensible**: Custom instructions for domain-specific acceleration
- **Modern HDL**: Chisel enables rapid iteration and verification

**Target customers**:
- Companies seeking licensing cost reduction
- Security-conscious customers (full source review)
- Researchers and academics
- Niche applications requiring custom instructions
- Open-source hardware projects

---

## Risk Mitigation

### Technical Risks

**Risk: Interrupt latency higher than expected**
- Mitigation: Profile early, optimize pipeline flushing, consider CLIC from Phase 0
- Contingency: Accept higher latency initially, optimize in Phase 3

**Risk: DMA complexity causes schedule delays**
- Mitigation: Start with simple memory-to-memory DMA, iterate to peripheral support
- Contingency: Ship Phase 1 without DMA, add in Phase 1.5

**Risk: ADC requires analog IP (not available in pure HDL)**
- Mitigation: Use FPGA primitives (XADC on Xilinx), or external ADC with SPI interface
- Contingency: Defer ADC to Phase 2, focus on digital peripherals

**Risk: Security features (TRNG, AES) require specialized hardware**
- Mitigation: Use proven open-source IP cores (e.g., OpenTitan components)
- Contingency: Ship without hardware acceleration, use software crypto initially

### Resource Risks

**Risk: FPGA resource exhaustion before Phase 3**
- Mitigation: Profile LUT/BRAM usage after each phase, optimize early
- Contingency: Target larger FPGA (Kintex-7 instead of Artix-7)

**Risk: Developer bandwidth insufficient for 9-11 month timeline**
- Mitigation: Parallelize independent tasks (e.g., SPI and I2C), use open-source IP where possible
- Contingency: Extend timeline or descope Phase 3 features

### Market Risks

**Risk: RISC-V ecosystem maturity lags ARM**
- Mitigation: Ensure compatibility with standard RISC-V toolchains (GCC, LLVM)
- Advantage: Rapid improvement in RISC-V tools, growing ecosystem

**Risk: ARM Cortex-M remains dominant due to software ecosystem**
- Mitigation: Target niches where open source is valued (academics, security-conscious customers)
- Advantage: No licensing fees, full auditability

---

## Next Steps

### Immediate Actions (Week 1)
1. **Review and approve this roadmap** with stakeholders
2. **Set up Phase 0 development branch** (`feature/phase0-interrupts`)
3. **Design PLIC interface** (register map, Chisel module structure)
4. **Spike trap handling** in simple testbench
5. **Update build system** for expanded SoC configuration
6. **Allocate memory map** for new peripherals (reserve address space)

### Development Kickoff (Week 2)
1. **Implement basic trap infrastructure**
   - Add mtvec, mepc, mcause CSRs
   - Trap entry/exit logic in pipeline
2. **Write interrupt unit tests**
   - CSR operations
   - Exception generation
3. **Begin PLIC implementation**
   - Gateway module (edge/level detection)
   - Priority and threshold logic

### Community Engagement
1. **Publish roadmap** on GitHub (as issue or discussion)
2. **Seek feedback** from RISC-V community
3. **Identify potential contributors** for specific features
4. **Document contribution guidelines** for peripheral development

---

## Appendix: Reference Designs

### Open-Source RISC-V MCUs for Reference
1. **SiFive E31** - RV32IMAC, CLIC, PLIC (commercial, but documented)
2. **PULPino** - Academic RISC-V MCU with peripherals
3. **Ibex (OpenTitan)** - RV32IMC, secure MCU (Google/lowRISC)
4. **VexRiscv** - Configurable RISC-V (SpinalHDL)

### Peripheral IP Sources
1. **OpenCores** - UART, SPI, I2C, GPIO (Verilog)
2. **OpenTitan** - Secure peripherals (UART, SPI, I2C, AES, TRNG) (SystemVerilog)
3. **FuseSoC** - Package manager for IP cores
4. **LiteX** - SoC builder with peripheral library (Python/Migen)

### RISC-V Specifications
1. **RISC-V Privileged Spec** - Exception and interrupt handling
2. **PLIC Specification** - Platform-Level Interrupt Controller
3. **CLIC Specification** - Core-Local Interrupt Controller (fast interrupts)
4. **PMP Specification** - Physical Memory Protection
5. **Debug Specification** - RISC-V External Debug Support

### Tools and Frameworks
1. **Chisel** - Hardware construction language (existing)
2. **ChiselTest** - Testing framework (existing)
3. **Verilator** - Fast simulator (existing)
4. **Vivado** - Xilinx FPGA synthesis and implementation
5. **FuseSoC** - Build system for HDL projects
6. **Renode** - Full-system emulator for RISC-V MCUs

---

## Conclusion

This roadmap provides a clear path from Svarog's current state as a capable RISC-V core to a **competitive low-end microcontroller** suitable for embedded, automotive, IoT, and smart home applications.

**Key Takeaways**:
1. **Phase 0 is critical** - Interrupts and timers are mandatory for embedded use
2. **Phase 1 achieves market competitiveness** - Rich peripherals match Cortex-M0+/M3
3. **Phase 2 targets premium markets** - Security and automotive features
4. **Phase 3 provides differentiation** - Performance and customization advantages

**Timeline**: 9-11 months for full implementation (Phases 0-3)

**Competitive Position**: After Phase 1, Svarog will be a **viable alternative to ARM Cortex-M0+/M3** for customers who value:
- Open-source transparency
- No licensing fees
- Customization capability
- Modern development practices (Chisel, Scala)

**Recommendation**: Proceed with Phase 0 immediately. This is the **highest-ROI investment** that unblocks embedded system development on Svarog.

---

**Document Prepared By**: Claude (Anthropic)
**For**: Svarog RISC-V Processor Project
**Contact**: See GitHub repository for project maintainers
