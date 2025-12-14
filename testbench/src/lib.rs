use std::{
    cell::RefCell,
    convert::TryInto,
    io::{BufRead, BufReader},
    path::Path,
    process::{Command, Stdio},
};

use anyhow::{Context, Result};
use cxx::UniquePtr;
use elf::{ElfBytes, endian::AnyEndian};
use snafu::Whatever;

mod bridge;
use bridge::ffi;

/// Register file state
#[derive(Debug, Clone)]
pub struct RegisterFile {
    regs: [u32; 32],
}

impl RegisterFile {
    pub fn new() -> Self {
        Self { regs: [0; 32] }
    }

    pub fn get(&self, idx: u8) -> u32 {
        if idx < 32 { self.regs[idx as usize] } else { 0 }
    }

    pub fn set(&mut self, idx: u8, value: u32) {
        if idx < 32 && idx != 0 {
            // x0 is always 0
            self.regs[idx as usize] = value;
        }
    }
}

impl Default for RegisterFile {
    fn default() -> Self {
        Self::new()
    }
}

/// Test result containing register state
#[derive(Debug)]
pub struct TestResult {
    pub regs: RegisterFile,
    pub exit_code: Option<u32>,
}

pub struct Simulator {
    model: RefCell<UniquePtr<ffi::VerilatorModel>>,
    timestamp: RefCell<u64>,
    vcd_open: RefCell<bool>,
}

impl Simulator {
    fn init_debug_interface(model: &mut UniquePtr<ffi::VerilatorModel>) {
        // Initialize all debug interface signals to safe defaults
        model.pin_mut().set_debug_hart_in_id_valid(0);
        model.pin_mut().set_debug_hart_in_id_bits(0);
        model.pin_mut().set_debug_hart_in_bits_halt_valid(0);
        model.pin_mut().set_debug_hart_in_bits_halt_bits(0);
        model.pin_mut().set_debug_hart_in_bits_breakpoint_valid(0);
        model.pin_mut().set_debug_hart_in_bits_breakpoint_bits_pc(0);
        model.pin_mut().set_debug_hart_in_bits_watchpoint_valid(0);
        model
            .pin_mut()
            .set_debug_hart_in_bits_watchpoint_bits_addr(0);
        model.pin_mut().set_debug_hart_in_bits_setPC_valid(0);
        model.pin_mut().set_debug_hart_in_bits_setPC_bits_pc(0);
        model.pin_mut().set_debug_hart_in_bits_register_valid(0);
        model.pin_mut().set_debug_hart_in_bits_register_bits_reg(0);
        model
            .pin_mut()
            .set_debug_hart_in_bits_register_bits_write(0);
        model.pin_mut().set_debug_hart_in_bits_register_bits_data(0);

        model.pin_mut().set_debug_mem_in_valid(0);
        model.pin_mut().set_debug_mem_in_bits_addr(0);
        model.pin_mut().set_debug_mem_in_bits_write(0);
        model.pin_mut().set_debug_mem_in_bits_data(0);
        model.pin_mut().set_debug_mem_in_bits_reqWidth(0); // BYTE
        model.pin_mut().set_debug_mem_in_bits_instr(0);

        model.pin_mut().set_debug_mem_res_ready(1); // Always ready to receive results
        model.pin_mut().set_debug_reg_res_ready(0); // Not ready until explicitly set
    }

    pub fn new() -> Result<Self, Whatever> {
        let mut model = ffi::create_verilator_model();

        // Initialize debug interface to safe defaults
        Self::init_debug_interface(&mut model);

        Ok(Simulator {
            model: RefCell::new(model),
            timestamp: RefCell::new(0),
            vcd_open: RefCell::new(false),
        })
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
        {
            let mut model = self.model.borrow_mut();

            // Establish initial state: clock low, then apply reset
            model.pin_mut().set_clock(0);
            model.pin_mut().set_reset(1);

            // Initialize debug interface first, THEN set halt
            // (init_debug_interface clears all signals including halt)
            Self::init_debug_interface(&mut model);

            // Set halt through debug interface
            // IMPORTANT: Must set id_valid and id_bits to route commands to hart 0
            model.pin_mut().set_debug_hart_in_id_valid(1);
            model.pin_mut().set_debug_hart_in_id_bits(0); // Hart 0
            model.pin_mut().set_debug_hart_in_bits_halt_valid(1);
            model.pin_mut().set_debug_hart_in_bits_halt_bits(1);

            // Set watchpoint if address was resolved
            if let Some(addr) = watchpoint_addr {
                model.pin_mut().set_debug_hart_in_bits_watchpoint_valid(1);
                model
                    .pin_mut()
                    .set_debug_hart_in_bits_watchpoint_bits_addr(addr);
                eprintln!("Setting watchpoint on address: 0x{:08x}", addr);
            }

            // Evaluate to apply reset before first clock edge
            model.pin_mut().eval();
        }

        // Reset for a few cycles
        for _ in 0..5 {
            self.tick(false);
        }

        // Take reset low before loading sections so the core starts from a clean
        // slate once we release halt later.
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_reset(0);
        }
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
            eprintln!("DEBUG: About to write word {} at addr 0x{:08x}", i, addr);
            self.write_mem_word(addr, word);
            eprintln!("DEBUG: Finished writing word {} at addr 0x{:08x}", i, addr);
            // Disable debug assertions for now - they interfere with the response handling
            // if cfg!(debug_assertions) && i < 4 {
            //     debug_assert_eq!(
            //         self.read_mem_word(addr),
            //         word,
            //         "memory verify failed at address 0x{:08x}",
            //         addr
            //     );
            // }
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
        // Open VCD trace
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().open_vcd(vcd_path.to_str().unwrap());
            *self.vcd_open.borrow_mut() = true;
        }

        // Toggle reset while dumping a couple of baseline cycles so the trace captures
        // the CPU at the architectural reset vector before we let the pipeline run.
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_reset(1);
        }
        for _ in 0..2 {
            self.tick(true);
        }
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_reset(0);
        }
        self.tick(true);

        // Set PC to program entry point and flush pipeline before releasing halt
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_debug_hart_in_id_valid(1);
            model.pin_mut().set_debug_hart_in_id_bits(0); // Hart 0
            model.pin_mut().set_debug_hart_in_bits_setPC_valid(1);
            model
                .pin_mut()
                .set_debug_hart_in_bits_setPC_bits_pc(0x80000000);
            eprintln!("Setting PC to 0x80000000 and flushing pipeline");
        }
        self.tick(true);
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_debug_hart_in_bits_setPC_valid(0);
        }
        self.tick(true);

        // Release halt to start execution
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_debug_mem_in_valid(0); // Disable memory writes
            model.pin_mut().set_debug_hart_in_id_valid(1);
            model.pin_mut().set_debug_hart_in_id_bits(0); // Hart 0
            model.pin_mut().set_debug_hart_in_bits_halt_valid(1);
            model.pin_mut().set_debug_hart_in_bits_halt_bits(0); // Release halt
            eprintln!("CPU halt released, starting execution");
        }
        self.tick(true);

        // Clear id.valid and halt.valid to enter "don't care" state
        // This allows internal events (watchpoints, breakpoints) to assert halt
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_debug_hart_in_id_valid(0);
            model.pin_mut().set_debug_hart_in_bits_halt_valid(0);
            eprintln!("Cleared halt.valid to 'don't care' state");
        }

        // Tick more cycles to fully clear pipeline after halt
        for _ in 0..10 {
            self.tick(true);
        }

        // Check if halt was actually released
        {
            let model = self.model.borrow();
            let halted = model.get_debug_halted() != 0;
            eprintln!("After release+10cycles: halted={}", halted);
        }

        for cycle in 0..max_cycles {
            self.tick(true);

            // Check if CPU has halted (watchpoint hit)
            let halted = {
                let model = self.model.borrow();
                model.get_debug_halted() != 0
            };

            if halted {
                eprintln!("CPU halted at cycle {}, watchpoint triggered", cycle);
                // Run a few more cycles to let the pipeline settle
                for _ in 0..5 {
                    self.tick(true);
                }
                break;
            }
        }

        // Close VCD
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().close_vcd();
            *self.vcd_open.borrow_mut() = false;
        }

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
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_debug_hart_in_id_valid(1);
            model.pin_mut().set_debug_hart_in_id_bits(0); // Hart 0
            model.pin_mut().set_debug_hart_in_bits_halt_valid(1);
            model.pin_mut().set_debug_hart_in_bits_halt_bits(1);
            model.pin_mut().set_debug_reg_res_ready(1); // Ready to receive results
        }

        // Tick to apply halt
        self.tick(false);

        // Read each register through debug interface
        for idx in 0..32 {
            {
                let mut model = self.model.borrow_mut();
                model.pin_mut().set_debug_hart_in_id_valid(1);
                model.pin_mut().set_debug_hart_in_id_bits(0); // Hart 0
                model.pin_mut().set_debug_hart_in_bits_register_valid(1);
                model
                    .pin_mut()
                    .set_debug_hart_in_bits_register_bits_reg(idx);
                model
                    .pin_mut()
                    .set_debug_hart_in_bits_register_bits_write(0); // Read
                model.pin_mut().set_debug_hart_in_bits_register_bits_data(0);
            }

            // Tick to process request
            self.tick(false);

            // Wait for result
            let mut attempts = 0;
            let val = loop {
                let model = self.model.borrow();
                if model.get_debug_reg_res_valid() != 0 {
                    break model.get_debug_reg_res_bits();
                }
                drop(model);

                attempts += 1;
                if attempts > 10 {
                    eprintln!("Warning: Timeout waiting for register {} read result", idx);
                    break 0;
                }
                self.tick(false);
            };

            regs.set(idx, val);

            // Clear register request
            {
                let mut model = self.model.borrow_mut();
                model.pin_mut().set_debug_hart_in_bits_register_valid(0);
            }
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
        eprintln!("\n>>> drive_mem_request START: addr=0x{:08x}, data=0x{:08x}, width={}, write={}",
                  addr, data, req_width, write);

        // Check initial state
        {
            let model = self.model.borrow();
            let initial_ready = model.get_debug_mem_in_ready() != 0;
            let initial_res_valid = model.get_debug_mem_res_valid() != 0;
            eprintln!("    Initial state: mem_in.ready={}, mem_res.valid={}", initial_ready, initial_res_valid);
        }

        // Wait for ready and send request
        let mut attempts = 0;
        loop {
            let ready = {
                let mut model = self.model.borrow_mut();
                model.pin_mut().set_debug_mem_in_bits_addr(addr);
                model
                    .pin_mut()
                    .set_debug_mem_in_bits_write(if write { 1 } else { 0 });
                model.pin_mut().set_debug_mem_in_bits_data(data);
                model.pin_mut().set_debug_mem_in_bits_reqWidth(req_width);
                model.pin_mut().set_debug_mem_in_bits_instr(0);
                model.pin_mut().set_debug_mem_in_valid(1);
                model.get_debug_mem_in_ready() != 0
            };
            self.tick(false);
            attempts += 1;
            if attempts == 1 {
                eprintln!("    Sent request, ready={}", ready);
            }
            if attempts > 10 {
                eprintln!("ERROR: drive_mem_request timeout waiting for ready, addr=0x{:08x}, write={}", addr, write);
                panic!("drive_mem_request timeout");
            }
            if ready {
                eprintln!("    Request accepted after {} attempts", attempts);
                break;
            }
        }

        // Clear request
        {
            let mut model = self.model.borrow_mut();
            model.pin_mut().set_debug_mem_in_valid(0);
            model.pin_mut().set_debug_mem_in_bits_write(0);
        }
        eprintln!("    Request cleared");

        // For writes, wait for response to complete before returning
        // For reads, the caller will wait for and consume the response
        if write {
            eprintln!("    Waiting for write response...");
            // Wait for response to arrive and memPending to clear
            // Check mem_in.ready to ensure memPending has cleared
            for attempt in 0..30 {
                self.tick(false);
                let (ready, mem_res_valid, mem_res_bits) = {
                    let model = self.model.borrow();
                    (model.get_debug_mem_in_ready() != 0,
                     model.get_debug_mem_res_valid() != 0,
                     model.get_debug_mem_res_bits())
                };
                eprintln!("    Write wait [{}]: ready={}, mem_res_valid={}, mem_res_bits=0x{:08x}",
                          attempt, ready, mem_res_valid, mem_res_bits);
                if ready {
                    eprintln!("    Write complete after {} attempts", attempt);
                    break;
                }
            }
        }
        eprintln!("<<< drive_mem_request END\n");
    }

    #[allow(dead_code)]
    pub fn read_mem_word(&self, addr: u32) -> u32 {
        eprintln!("DEBUG: read_mem_word addr=0x{:08x}", addr);
        self.drive_mem_request(addr, 0, 2, false);

        let mut attempts = 0;
        loop {
            let response = {
                let model = self.model.borrow();
                if model.get_debug_mem_res_valid() != 0 {
                    Some(model.get_debug_mem_res_bits())
                } else {
                    None
                }
            };

            if let Some(val) = response {
                eprintln!("DEBUG: read_mem_word got response: 0x{:08x}", val);
                return val;
            }

            self.tick(false);
            attempts += 1;
            if attempts > 20 {
                eprintln!("ERROR: read_mem_word timeout waiting for response, addr=0x{:08x}", addr);
                panic!("read_mem_word timeout");
            }
        }
    }

    fn tick(&self, dump_vcd: bool) {
        let mut model = self.model.borrow_mut();
        model.pin_mut().set_clock(0);
        model.pin_mut().eval();
        if dump_vcd && *self.vcd_open.borrow() {
            model.pin_mut().dump_vcd(*self.timestamp.borrow());
        }
        *(self.timestamp.borrow_mut()) += 1;

        model.pin_mut().set_clock(1);
        model.pin_mut().eval();

        if dump_vcd && *self.vcd_open.borrow() {
            model.pin_mut().dump_vcd(*self.timestamp.borrow());
        }
        *(self.timestamp.borrow_mut()) += 1;
    }
}

/// Run test in Spike and return register state
pub fn run_spike_test(elf_path: &Path, watchpoint_addr: Option<u32>) -> Result<TestResult> {
    let mut child = Command::new("spike")
        .args(&["--isa=RV32I", "-l", "--log-commits"])
        .arg(elf_path)
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .context("Failed to run spike")?;

    let stderr = child
        .stderr
        .take()
        .ok_or_else(|| anyhow::anyhow!("Failed to capture spike stderr"))?;
    let reader = BufReader::new(stderr);
    let mut regs = RegisterFile::new();

    let mut lines_seen = 0usize;
    let mut hit_watchpoint = false;
    for line in reader.lines() {
        let line = line?;
        lines_seen += 1;
        if let Some(reg_write) = parse_spike_reg_write(&line) {
            regs.set(reg_write.0, reg_write.1);
        }

        if let Some(addr) = parse_spike_mem_write(&line) {
            if Some(addr) == watchpoint_addr {
                // Test reached tohost; stop spike execution.
                let _ = child.kill();
                hit_watchpoint = true;
                break;
            }
        }

        if lines_seen > 1_000_000 {
            let _ = child.kill();
            anyhow::bail!(
                "Spike did not reach tohost (addr=0x{:08x}) within log limit",
                watchpoint_addr.unwrap_or(0)
            );
        }
    }

    // Wait for spike to exit (ignore errors)
    let _ = child.wait();

    if watchpoint_addr.is_some() && !hit_watchpoint {
        anyhow::bail!(
            "Spike terminated without hitting tohost (addr=0x{:08x})",
            watchpoint_addr.unwrap()
        );
    }

    Ok(TestResult {
        regs,
        exit_code: None,
    })
}

/// Parse a single spike register write line
/// Returns (register_index, value) if successful
fn parse_spike_reg_write(line: &str) -> Option<(u8, u32)> {
    let parts: Vec<&str> = line.split_whitespace().collect();
    let mut i = 0;

    while i < parts.len() {
        let part = parts[i];

        // Case 1: token is exactly "x" and next token is the register number
        if part == "x" && i + 2 < parts.len() {
            if let (Ok(reg_num), Some(value)) =
                (parts[i + 1].parse::<u8>(), parse_hex(parts[i + 2]))
            {
                return Some((reg_num, value));
            }
        }

        // Case 2: token looks like "x5"
        if let Some(reg_str) = part.strip_prefix('x') {
            if let (Ok(reg_num), Some(value)) = (
                reg_str.parse::<u8>(),
                parts.get(i + 1).and_then(|token| parse_hex(token)),
            ) {
                return Some((reg_num, value));
            }
        }

        i += 1;
    }

    None
}

fn parse_hex(token: &str) -> Option<u32> {
    let trimmed = token
        .trim_start_matches('(')
        .trim_end_matches(')')
        .trim_start_matches("0x");
    if trimmed.is_empty() {
        return None;
    }
    u32::from_str_radix(trimmed, 16).ok()
}

fn parse_spike_mem_write(line: &str) -> Option<u32> {
    let parts: Vec<&str> = line.split_whitespace().collect();
    for i in 0..parts.len() {
        if parts[i] == "mem" && i + 2 < parts.len() {
            if let Some(addr) = parse_hex(parts[i + 1]) {
                return Some(addr);
            }
        }
    }
    None
}

/// Compare Verilator and Spike results
pub fn compare_results(verilator: &TestResult, spike: &TestResult) -> Result<()> {
    let mut mismatches = Vec::new();

    // Compare all registers (except x0 which is always 0)
    for i in 1..32 {
        let v_val = verilator.regs.get(i);
        let s_val = spike.regs.get(i);

        if v_val != s_val {
            mismatches.push(format!(
                "x{}: verilator=0x{:08x}, spike=0x{:08x}",
                i, v_val, s_val
            ));
        }
    }

    if !mismatches.is_empty() {
        anyhow::bail!(
            "Register mismatches (x30 verilator=0x{:08x}, spike=0x{:08x}):\n{}",
            verilator.regs.get(30),
            spike.regs.get(30),
            mismatches.join("\n")
        );
    }

    Ok(())
}
