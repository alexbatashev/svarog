use std::{cell::RefCell, convert::TryInto, path::Path};

use anyhow::{Context, Result};
use elf::{ElfBytes, endian::AnyEndian};

use crate::models::{VerilatorModelVariant, create_model};
use crate::uart::UartDecoder;
use crate::{ModelId, RegisterFile, TestResult};

/// RTC clock divider - rtcClock runs 50x slower than main clock
const RTC_CLOCK_DIVIDER: u64 = 50;

pub struct Simulator {
    model: VerilatorModelVariant,
    timestamp: RefCell<u64>,
    vcd_open: RefCell<bool>,
    uart_decoder: RefCell<Option<(usize, UartDecoder)>>, // (uart_index, decoder)
    rtc_counter: RefCell<u64>,                           // Counter for RTC clock division
}

impl Simulator {
    fn init_debug_interface(model: &VerilatorModelVariant) {
        // Initialize all debug interface signals to safe defaults
        model.set_debug_hart_in_id_valid(0);
        model.set_debug_hart_in_id_bits(0);
        model.set_debug_hart_in_bits_halt_valid(0);
        model.set_debug_hart_in_bits_halt_bits(0);
        model.set_debug_hart_in_bits_breakpoint_valid(0);
        model.set_debug_hart_in_bits_breakpoint_bits_pc(0);
        model.set_debug_hart_in_bits_watchpoint_valid(0);
        model.set_debug_hart_in_bits_watchpoint_bits_addr(0);
        model.set_debug_hart_in_bits_setPC_valid(0);
        model.set_debug_hart_in_bits_setPC_bits_pc(0);
        model.set_debug_hart_in_bits_register_valid(0);
        model.set_debug_hart_in_bits_register_bits_reg(0);
        model.set_debug_hart_in_bits_register_bits_write(0);
        model.set_debug_hart_in_bits_register_bits_data(0);

        model.set_debug_mem_in_valid(0);
        model.set_debug_mem_in_bits_addr(0);
        model.set_debug_mem_in_bits_write(0);
        model.set_debug_mem_in_bits_data(0);
        model.set_debug_mem_in_bits_reqWidth(0); // BYTE
        model.set_debug_mem_in_bits_instr(0);

        model.set_debug_mem_res_ready(1); // Always ready to receive results
        model.set_debug_reg_res_ready(0); // Not ready until explicitly set
    }

    pub fn new(model_id: ModelId) -> Result<Self> {
        let model = create_model(model_id).context("Failed to create Verilator model")?;

        // Initialize debug interface to safe defaults
        Self::init_debug_interface(&model);

        Ok(Simulator {
            model,
            timestamp: RefCell::new(0),
            vcd_open: RefCell::new(false),
            uart_decoder: RefCell::new(None),
            rtc_counter: RefCell::new(0),
        })
    }

    /// Enable UART console monitoring
    ///
    /// When enabled, the simulator will decode UART TX output from the specified
    /// UART index and print it as ASCII characters during simulation.
    ///
    /// # Arguments
    /// * `uart_index` - Which UART to monitor (0 or 1)
    pub fn enable_uart_console(&self, uart_index: usize) {
        *self.uart_decoder.borrow_mut() = Some((uart_index, UartDecoder::new()));
        eprintln!("UART console monitoring enabled for UART {}", uart_index);
    }

    /// Load a raw binary file at a specific address
    pub fn load_raw_binary<P: AsRef<Path>>(
        &self,
        path: P,
        load_addr: u32,
        entry_point: Option<u32>,
        watchpoint_addr: Option<u32>,
    ) -> Result<u32> {
        let file_data = std::fs::read(path.as_ref()).context("Failed to read binary file")?;

        eprintln!(
            "Loading raw binary {} ({} bytes) at address 0x{:08x}",
            path.as_ref().display(),
            file_data.len(),
            load_addr
        );

        // Reset and initialize
        self.model.set_clock(0);
        self.model.set_reset(1);
        Self::init_debug_interface(&self.model);

        // Set halt
        self.model.set_debug_hart_in_id_valid(1);
        self.model.set_debug_hart_in_id_bits(0);
        self.model.set_debug_hart_in_bits_halt_valid(1);
        self.model.set_debug_hart_in_bits_halt_bits(1);

        // Set watchpoint if provided
        if let Some(addr) = watchpoint_addr {
            self.model.set_debug_hart_in_bits_watchpoint_valid(1);
            self.model.set_debug_hart_in_bits_watchpoint_bits_addr(addr);
            eprintln!("Setting watchpoint on address: 0x{:08x}", addr);
        }

        self.model.eval();

        // Reset for a few cycles
        for _ in 0..5 {
            self.tick(false);
        }

        // Take reset low
        self.model.set_reset(0);
        self.tick(false);

        // Load binary data to memory
        self.upload_raw_binary(&file_data, load_addr);

        // Return entry point (use load_addr if not specified)
        Ok(entry_point.unwrap_or(load_addr))
    }

    pub fn load_binary<P: AsRef<Path>>(
        &self,
        path: P,
        watchpoint_symbol: Option<&str>,
    ) -> anyhow::Result<Option<u32>> {
        let file_data = std::fs::read(path)?;
        let slice = file_data.as_slice();
        let file = ElfBytes::<AnyEndian>::minimal_parse(slice)?;

        // Resolve watchpoint symbol address if provided
        let watchpoint_addr = if let Some(symbol_name) = watchpoint_symbol {
            if let Some(symtab) = file.symbol_table()? {
                let mut found_addr = None;
                for symbol in symtab.0.iter() {
                    if let Ok(name) = symtab.1.get(symbol.st_name as usize) {
                        if name == symbol_name {
                            found_addr = Some(symbol.st_value as u32);
                            eprintln!(
                                "Found symbol '{}' at address 0x{:08x}",
                                symbol_name, symbol.st_value
                            );
                            break;
                        }
                    }
                }
                found_addr
            } else {
                eprintln!("Warning: No symbol table found in ELF file");
                None
            }
        } else {
            None
        };

        // IMPORTANT: Reset FIRST before loading memory!
        // Memory uses RegInit, so reset clears it to all zeros.
        // We must reset first, then load memory after.

        // Establish initial state: clock low, then apply reset
        self.model.set_clock(0);
        self.model.set_reset(1);

        // Initialize debug interface first, THEN set halt
        // (init_debug_interface clears all signals including halt)
        Self::init_debug_interface(&self.model);

        // Set halt through debug interface
        // IMPORTANT: Must set id_valid and id_bits to route commands to hart 0
        self.model.set_debug_hart_in_id_valid(1);
        self.model.set_debug_hart_in_id_bits(0); // Hart 0
        self.model.set_debug_hart_in_bits_halt_valid(1);
        self.model.set_debug_hart_in_bits_halt_bits(1);

        // Set watchpoint if address was resolved
        if let Some(addr) = watchpoint_addr {
            self.model.set_debug_hart_in_bits_watchpoint_valid(1);
            self.model.set_debug_hart_in_bits_watchpoint_bits_addr(addr);
            eprintln!("Setting watchpoint on address: 0x{:08x}", addr);
        }

        // Evaluate to apply reset before first clock edge
        self.model.eval();

        // Reset for a few cycles
        for _ in 0..5 {
            self.tick(false);
        }

        // Take reset low before loading sections so the core starts from a clean
        // slate once we release halt later.
        self.model.set_reset(0);
        self.tick(false);

        // Load sections: .text, .text.init, and .data
        let sections_to_load = [".text", ".text.init", ".data"];
        for section_name in &sections_to_load {
            if let Some(section_hdr) = file.section_header_by_name(section_name)? {
                let (data, _) = file.section_data(&section_hdr)?;
                let start_addr = section_hdr.sh_addr as u32;
                self.upload_section(section_name, data, start_addr);
            } else {
                eprintln!("Warning: Section {} not found in ELF file", section_name);
            }
        }

        Ok(watchpoint_addr)
    }

    fn upload_section(&self, section_name: &str, data: &[u8], start_addr: u32) {
        eprintln!(
            "Loading section {} ({} bytes) starting at address 0x{:08x}",
            section_name,
            data.len(),
            start_addr
        );

        let mut chunk_iter = data.chunks_exact(4);
        for (i, chunk) in chunk_iter.by_ref().enumerate() {
            let word = u32::from_le_bytes(chunk.try_into().unwrap());
            let addr = start_addr + (i as u32 * 4);
            if i < 10 {
                eprintln!("  [0x{:08x}] = 0x{:08x}", addr, word);
            }
            self.write_mem_word(addr, word);
        }

        let remainder = chunk_iter.remainder();
        if !remainder.is_empty() {
            let start_offset = (data.len() - remainder.len()) as u32;
            for (byte_offset, byte) in remainder.iter().enumerate() {
                let addr = start_addr + start_offset + byte_offset as u32;
                self.write_mem_byte(addr, *byte);
            }
        }
    }

    fn upload_raw_binary(&self, data: &[u8], start_addr: u32) {
        let mut chunk_iter = data.chunks_exact(4);
        for (i, chunk) in chunk_iter.by_ref().enumerate() {
            let word = u32::from_le_bytes(chunk.try_into().unwrap());
            let addr = start_addr + (i as u32 * 4);
            if i < 10 {
                eprintln!("  [0x{:08x}] = 0x{:08x}", addr, word);
            }
            self.write_mem_word(addr, word);
        }

        let remainder = chunk_iter.remainder();
        if !remainder.is_empty() {
            let start_offset = (data.len() - remainder.len()) as u32;
            for (byte_offset, byte) in remainder.iter().enumerate() {
                let addr = start_addr + start_offset + byte_offset as u32;
                self.write_mem_byte(addr, *byte);
            }
        }
    }

    pub fn run(&self, vcd_path: &Path, max_cycles: usize) -> Result<TestResult> {
        self.run_with_entry_point(vcd_path, max_cycles, 0x80000000)
    }

    pub fn run_with_entry_point(
        &self,
        vcd_path: &Path,
        max_cycles: usize,
        entry_point: u32,
    ) -> Result<TestResult> {
        // Open VCD trace
        self.model.open_vcd(vcd_path.to_str().unwrap());
        *self.vcd_open.borrow_mut() = true;

        // Toggle reset while dumping a couple of baseline cycles so the trace captures
        // the CPU at the architectural reset vector before we let the pipeline run.
        self.model.set_reset(1);
        for _ in 0..2 {
            self.tick(true);
        }
        self.model.set_reset(0);
        self.tick(true);

        // Set PC to program entry point and flush pipeline before releasing halt
        self.model.set_debug_hart_in_id_valid(1);
        self.model.set_debug_hart_in_id_bits(0); // Hart 0
        self.model.set_debug_hart_in_bits_setPC_valid(1);
        self.model.set_debug_hart_in_bits_setPC_bits_pc(entry_point);
        eprintln!("Setting PC to 0x{:08x} and flushing pipeline", entry_point);
        self.tick(true);
        self.model.set_debug_hart_in_bits_setPC_valid(0);
        self.tick(true);

        // Release halt to start execution
        self.model.set_debug_mem_in_valid(0); // Disable memory writes
        self.model.set_debug_hart_in_id_valid(1);
        self.model.set_debug_hart_in_id_bits(0); // Hart 0
        self.model.set_debug_hart_in_bits_halt_valid(1);
        self.model.set_debug_hart_in_bits_halt_bits(0); // Release halt
        eprintln!("CPU halt released, starting execution");
        self.tick(true);

        // Clear id.valid and halt.valid to enter "don't care" state
        // This allows internal events (watchpoints, breakpoints) to assert halt
        self.model.set_debug_hart_in_id_valid(0);
        self.model.set_debug_hart_in_bits_halt_valid(0);
        eprintln!("Cleared halt.valid to 'don't care' state");

        // Tick more cycles to fully clear pipeline after halt
        for _ in 0..10 {
            self.tick(true);
        }

        // Check if halt was actually released
        let halted = self.model.get_debug_halted() != 0;
        eprintln!("After release+10cycles: halted={}", halted);

        for cycle in 0..max_cycles {
            self.tick(true);

            // Sample UART TX if console monitoring is enabled
            if let Some((uart_index, decoder)) = &mut *self.uart_decoder.borrow_mut() {
                let txd = match uart_index {
                    0 => self.model.get_uart_0_txd(),
                    1 => self.model.get_uart_1_txd(),
                    _ => 0,
                };

                if let Some(byte) = decoder.process(txd) {
                    // Print the decoded byte as ASCII
                    print!("{}", byte as char);
                    std::io::Write::flush(&mut std::io::stdout()).ok();
                }
            }

            // Check if CPU has halted (watchpoint hit)
            let halted = self.model.get_debug_halted() != 0;

            if halted {
                eprintln!("\nCPU halted at cycle {}, watchpoint triggered", cycle);
                // Run a few more cycles to let the pipeline settle
                for _ in 0..5 {
                    self.tick(true);
                }
                break;
            }
        }

        // Close VCD
        self.model.close_vcd();
        *self.vcd_open.borrow_mut() = false;

        let regs = self.capture_registers()?;
        let exit_code = regs.get(3); // x3/gp holds test result

        Ok(TestResult {
            regs,
            exit_code: Some(exit_code),
        })
    }

    fn capture_registers(&self) -> Result<RegisterFile> {
        let mut regs = RegisterFile::new();

        // Ensure CPU is halted
        self.model.set_debug_hart_in_id_valid(1);
        self.model.set_debug_hart_in_id_bits(0); // Hart 0
        self.model.set_debug_hart_in_bits_halt_valid(1);
        self.model.set_debug_hart_in_bits_halt_bits(1);
        self.model.set_debug_reg_res_ready(1); // Ready to receive results

        // Tick to apply halt
        self.tick(false);

        // Read each register through debug interface
        for idx in 0..32 {
            self.model.set_debug_hart_in_id_valid(1);
            self.model.set_debug_hart_in_id_bits(0); // Hart 0
            self.model.set_debug_hart_in_bits_register_valid(1);
            self.model.set_debug_hart_in_bits_register_bits_reg(idx);
            self.model.set_debug_hart_in_bits_register_bits_write(0); // Read
            self.model.set_debug_hart_in_bits_register_bits_data(0);

            // Tick to process request
            self.tick(false);

            // Wait for result
            let val = loop {
                if self.model.get_debug_reg_res_valid() != 0 {
                    break self.model.get_debug_reg_res_bits();
                }

                self.tick(false);
            };

            regs.set(idx, val);

            // Clear register request
            self.model.set_debug_hart_in_bits_register_valid(0);
        }

        Ok(regs)
    }

    fn write_mem_byte(&self, addr: u32, data: u8) {
        self.drive_mem_request(addr, data as u32, 0, true);
    }

    fn write_mem_word(&self, addr: u32, data: u32) {
        self.drive_mem_request(addr, data, 2, true);
    }

    fn drive_mem_request(&self, addr: u32, data: u32, req_width: u8, write: bool) {
        // Wait for ready and send request
        loop {
            self.model.set_debug_mem_in_bits_addr(addr);
            self.model
                .set_debug_mem_in_bits_write(if write { 1 } else { 0 });
            self.model.set_debug_mem_in_bits_data(data);
            self.model.set_debug_mem_in_bits_reqWidth(req_width);
            self.model.set_debug_mem_in_bits_instr(0);
            self.model.set_debug_mem_in_valid(1);
            let ready = self.model.get_debug_mem_in_ready() != 0;
            self.tick(false);
            if ready {
                break;
            }
        }

        // Clear request
        self.model.set_debug_mem_in_valid(0);
        self.model.set_debug_mem_in_bits_write(0);

        // For writes, wait for response to complete before returning
        // For reads, the caller will wait for and consume the response
        if write {
            // Wait for response to arrive and memPending to clear
            // Check mem_in.ready to ensure memPending has cleared
            for _ in 0..30 {
                self.tick(false);
                let ready = self.model.get_debug_mem_in_ready() != 0;
                if ready {
                    break;
                }
            }
        }
    }

    #[allow(dead_code)]
    pub fn read_mem_word(&self, addr: u32) -> u32 {
        self.drive_mem_request(addr, 0, 2, false);

        let mut attempts = 0;
        loop {
            let response = if self.model.get_debug_mem_res_valid() != 0 {
                Some(self.model.get_debug_mem_res_bits())
            } else {
                None
            };

            if let Some(val) = response {
                return val;
            }

            self.tick(false);
            attempts += 1;
            if attempts > 20 {
                panic!("read_mem_word timeout");
            }
        }
    }

    fn tick(&self, dump_vcd: bool) {
        // Update RTC clock - runs at 1/50th of main clock frequency
        let mut rtc_counter = self.rtc_counter.borrow_mut();
        *rtc_counter += 1;
        if *rtc_counter >= RTC_CLOCK_DIVIDER {
            *rtc_counter = 0;
            // Toggle RTC clock
            let rtc_clk = self.model.get_rtc_clock();
            self.model.set_rtc_clock(if rtc_clk == 0 { 1 } else { 0 });
        }
        drop(rtc_counter);

        self.model.set_clock(0);
        self.model.eval();
        if dump_vcd && *self.vcd_open.borrow() {
            self.model.dump_vcd(*self.timestamp.borrow());
        }
        *(self.timestamp.borrow_mut()) += 1;

        self.model.set_clock(1);
        self.model.eval();

        if dump_vcd && *self.vcd_open.borrow() {
            self.model.dump_vcd(*self.timestamp.borrow());
        }
        *(self.timestamp.borrow_mut()) += 1;
    }
}
