use std::{
    cell::RefCell,
    collections::HashMap,
    convert::TryInto,
    path::Path,
    process::Command,
};

use anyhow::{Context, Result};
use elf::{ElfBytes, endian::AnyEndian};
use marlin::{
    verilator::{
        AsVerilatedModel, VerilatedModelConfig, VerilatorRuntime, VerilatorRuntimeOptions, vcd::Vcd,
    },
    verilog::prelude::*,
};
use snafu::Whatever;

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

#[verilog(src = "../target/generated/VerilatorTop.sv", name = "VerilatorTop")]
pub struct VerilatorTop;

pub struct Simulator {
    runtime: &'static VerilatorRuntime,
    pub model: RefCell<VerilatorTop<'static>>,
    pub timestamp: RefCell<u64>,
}

impl Simulator {
    fn init_debug_interface(dut: &mut VerilatorTop) {
        // Initialize all debug interface signals to safe defaults
        dut.io_debug_hart_in_id_valid = 0;
        dut.io_debug_hart_in_id_bits = 0;
        dut.io_debug_hart_in_bits_halt_valid = 0;
        dut.io_debug_hart_in_bits_halt_bits = 0;
        dut.io_debug_hart_in_bits_breakpoint_valid = 0;
        dut.io_debug_hart_in_bits_breakpoint_bits_pc = 0;
        dut.io_debug_hart_in_bits_register_valid = 0;
        dut.io_debug_hart_in_bits_register_bits_reg = 0;
        dut.io_debug_hart_in_bits_register_bits_write = 0;
        dut.io_debug_hart_in_bits_register_bits_data = 0;

        dut.io_debug_mem_in_valid = 0;
        dut.io_debug_mem_in_bits_addr = 0;
        dut.io_debug_mem_in_bits_write = 0;
        dut.io_debug_mem_in_bits_data = 0;
        dut.io_debug_mem_in_bits_reqWidth = 0; // BYTE
        dut.io_debug_mem_in_bits_instr = 0;

        dut.io_debug_mem_res_ready = 1; // Always ready to receive results
    }

    pub fn new(out_dir: &str) -> Result<Self, Whatever> {
        // Leak the runtime to get a 'static reference
        // This is necessary because the model needs to borrow from the runtime
        // for its entire lifetime, creating a self-referential structure
        let runtime = Box::new(VerilatorRuntime::new(
            out_dir.into(),
            &[VerilatorTop::source_path().into()],
            &[],
            [],
            VerilatorRuntimeOptions::default_logging(),
        )?);
        let runtime: &'static VerilatorRuntime = Box::leak(runtime);

        let model = RefCell::new(runtime.create_model::<VerilatorTop<'static>>(
            &VerilatedModelConfig {
                verilator_optimization: 3,
                enable_tracing: true,
                ..Default::default()
            },
        )?);

        // Initialize debug interface to safe defaults
        {
            let mut dut = model.borrow_mut();
            Self::init_debug_interface(&mut dut);
        }

        Ok(Simulator {
            runtime,
            model,
            timestamp: RefCell::new(0),
        })
    }

    pub fn load_binary<P: AsRef<Path>>(&self, path: P) -> anyhow::Result<()> {
        let file_data = std::fs::read(path)?;
        let slice = file_data.as_slice();
        let file = ElfBytes::<AnyEndian>::minimal_parse(slice)?;

        // IMPORTANT: Reset FIRST before loading memory!
        // Memory uses RegInit, so reset clears it to all zeros.
        // We must reset first, then load memory after.
        {
            let mut dut = self.model.borrow_mut();

            // Establish initial state: clock low, then apply reset
            dut.clock = 0;
            dut.reset = 1;

            // Initialize debug interface first, THEN set halt
            // (init_debug_interface clears all signals including halt)
            Self::init_debug_interface(&mut dut);

            // Set halt through debug interface
            // IMPORTANT: Must set id_valid and id_bits to route commands to hart 0
            dut.io_debug_hart_in_id_valid = 1;
            dut.io_debug_hart_in_id_bits = 0; // Hart 0
            dut.io_debug_hart_in_bits_halt_valid = 1;
            dut.io_debug_hart_in_bits_halt_bits = 1;

            // Evaluate to apply reset before first clock edge
            dut.eval();
        }

        // Reset for a few cycles
        for _ in 0..5 {
            self.tick(&mut None);
        }

        // Take reset low before loading sections so the core starts from a clean
        // slate once we release halt later.
        {
            let mut dut = self.model.borrow_mut();
            dut.reset = 0;
        }
        self.tick(&mut None);

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

        Ok(())
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
            if cfg!(debug_assertions) && i < 4 {
                debug_assert_eq!(
                    self.read_mem_word(addr),
                    word,
                    "memory verify failed at address 0x{:08x}",
                    addr
                );
            }
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
        let mut vcd = { self.model.borrow_mut().open_vcd(vcd_path) };

        // Toggle reset while dumping a couple of baseline cycles so the trace captures
        // the CPU at the architectural reset vector before we let the pipeline run.
        {
            let mut dut = self.model.borrow_mut();
            dut.reset = 1;
        }
        for _ in 0..2 {
            self.tick(&mut Some(&mut vcd));
        }
        {
            let mut dut = self.model.borrow_mut();
            dut.reset = 0;
        }
        self.tick(&mut Some(&mut vcd));

        // Release halt to start execution
        {
            let mut dut = self.model.borrow_mut();
            dut.io_debug_mem_in_valid = 0; // Disable memory writes
            dut.io_debug_hart_in_id_valid = 1;
            dut.io_debug_hart_in_id_bits = 0; // Hart 0
            dut.io_debug_hart_in_bits_halt_valid = 1;
            dut.io_debug_hart_in_bits_halt_bits = 0; // Release halt
            eprintln!("CPU halt released, starting execution");
        }

        // Tick more cycles to fully clear pipeline after halt
        for _ in 0..10 {
            self.tick(&mut Some(&mut vcd));
        }

        for _ in 0..max_cycles {
            self.tick(&mut Some(&mut vcd));
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
            let mut dut = self.model.borrow_mut();
            dut.io_debug_hart_in_id_valid = 1;
            dut.io_debug_hart_in_id_bits = 0; // Hart 0
            dut.io_debug_hart_in_bits_halt_valid = 1;
            dut.io_debug_hart_in_bits_halt_bits = 1;
            dut.io_debug_reg_res_ready = 1; // Ready to receive results
        }

        // Tick to apply halt
        self.tick(&mut None);

        // Read each register through debug interface
        for idx in 0..32 {
            {
                let mut dut = self.model.borrow_mut();
                dut.io_debug_hart_in_id_valid = 1;
                dut.io_debug_hart_in_id_bits = 0; // Hart 0
                dut.io_debug_hart_in_bits_register_valid = 1;
                dut.io_debug_hart_in_bits_register_bits_reg = idx;
                dut.io_debug_hart_in_bits_register_bits_write = 0; // Read
                dut.io_debug_hart_in_bits_register_bits_data = 0;
            }

            // Tick to process request
            self.tick(&mut None);

            // Wait for result
            let mut attempts = 0;
            let val = loop {
                let dut = self.model.borrow();
                if dut.io_debug_reg_res_valid != 0 {
                    break dut.io_debug_reg_res_bits;
                }
                drop(dut);

                attempts += 1;
                if attempts > 10 {
                    eprintln!("Warning: Timeout waiting for register {} read result", idx);
                    break 0;
                }
                self.tick(&mut None);
            };

            regs.set(idx, val);

            // Clear register request
            {
                let mut dut = self.model.borrow_mut();
                dut.io_debug_hart_in_bits_register_valid = 0;
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
        loop {
            let ready = {
                let mut dut = self.model.borrow_mut();
                dut.io_debug_mem_in_bits_addr = addr;
                dut.io_debug_mem_in_bits_write = if write { 1 } else { 0 };
                dut.io_debug_mem_in_bits_data = data;
                dut.io_debug_mem_in_bits_reqWidth = req_width;
                dut.io_debug_mem_in_bits_instr = 0;
                dut.io_debug_mem_in_valid = 1;
                dut.io_debug_mem_in_ready != 0
            };
            self.tick(&mut None);
            if ready {
                break;
            }
        }
        {
            let mut dut = self.model.borrow_mut();
            dut.io_debug_mem_in_valid = 0;
            dut.io_debug_mem_in_bits_write = 0;
        }
    }

    #[allow(dead_code)]
    fn read_mem_word(&self, addr: u32) -> u32 {
        self.drive_mem_request(addr, 0, 2, false);

        loop {
            let response = {
                let dut = self.model.borrow();
                if dut.io_debug_mem_res_valid != 0 {
                    Some(dut.io_debug_mem_res_bits)
                } else {
                    None
                }
            };

            if let Some(val) = response {
                return val;
            }

            self.tick(&mut None);
        }
    }

    fn tick(&self, vcd: &mut Option<&mut Vcd<'static>>) {
        let mut dut = self.model.borrow_mut();
        dut.clock = 0;
        dut.eval();
        vcd.as_mut().map(|vcd| vcd.dump(*self.timestamp.borrow()));
        *(self.timestamp.borrow_mut()) += 1;

        dut.clock = 1;
        dut.eval();

        vcd.as_mut().map(|vcd| vcd.dump(*self.timestamp.borrow()));
        *(self.timestamp.borrow_mut()) += 1;
    }
}

/// Run test in Spike and return register state
pub fn run_spike_test(elf_path: &Path) -> Result<TestResult> {
    // Run spike with register dumping
    let output = Command::new("spike")
        .args(&["--isa=RV32I", "-l", "--log-commits"])
        .arg(elf_path)
        .output()
        .context("Failed to run spike")?;

    // Parse spike output to extract register state
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);

    let regs = parse_spike_output(&stdout, &stderr)?;

    Ok(TestResult {
        regs,
        exit_code: None,
    })
}

/// Parse spike output to extract final register state
fn parse_spike_output(stdout: &str, stderr: &str) -> Result<RegisterFile> {
    let mut regs = RegisterFile::new();
    let mut reg_map: HashMap<u8, u32> = HashMap::new();

    for line in stdout.lines().chain(stderr.lines()) {
        // Look for lines with register writes
        if let Some(reg_write) = parse_spike_reg_write(line) {
            reg_map.insert(reg_write.0, reg_write.1);
        }
    }

    // Apply all register writes
    for (idx, value) in reg_map {
        regs.set(idx, value);
    }

    Ok(regs)
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
