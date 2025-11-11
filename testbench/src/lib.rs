use std::{cell::RefCell, collections::HashMap, path::Path, process::Command};

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
    pub cycles: usize,
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
        {
            let mut dut = model.borrow_mut();
            dut.tohost_addr = 0;
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
            dut.reset = 1;
            dut.halt = 1;
            dut.regfile_read_en = 0;
            dut.mem_write_en = 0;
        }

        // Reset for a few cycles
        for _ in 0..5 {
            self.tick(&mut None);
        }

        // Release reset
        {
            let mut dut = self.model.borrow_mut();
            dut.reset = 0;
        }

        // Wait a cycle after reset
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

        let tohost_addr = file
            .section_header_by_name(".tohost")?
            .map(|hdr| hdr.sh_addr as u32)
            .unwrap_or(0);
        {
            let mut dut = self.model.borrow_mut();
            dut.tohost_addr = tohost_addr;
        }

        // One more cycle after loading
        self.tick(&mut None);

        Ok(())
    }

    fn upload_section(&self, section_name: &str, data: &[u8], start_addr: u32) {
        let data_words: &[u32] = bytemuck::cast_slice(data);

        eprintln!(
            "Loading section {} ({} bytes) starting at address 0x{:08x}",
            section_name,
            data.len(),
            start_addr
        );

        for (i, word) in data_words.iter().enumerate() {
            let addr = start_addr + (i as u32 * 4);
            if i < 10 {
                eprintln!("  [0x{:08x}] = 0x{:08x}", addr, word);
            }
            // Write word as 4 bytes (little-endian)
            let bytes = word.to_le_bytes();
            for (byte_offset, byte) in bytes.iter().enumerate() {
                self.write_mem_byte(addr + byte_offset as u32, *byte);
            }
        }
    }

    pub fn run(&self, vcd_path: &Path, max_cycles: usize) -> Result<TestResult> {
        // Just ensure write signals are disabled and release halt to start execution
        {
            let mut dut = self.model.borrow_mut();
            dut.mem_write_en = 0;
            dut.halt = 0; // Release halt to start CPU
            eprintln!("CPU halt released, starting execution");
        }

        // Tick more cycles to fully clear pipeline after halt
        for _ in 0..10 {
            self.tick(&mut None);
        }

        // Run simulation
        let mut failure_cycle = None;
        let mut completion_detected_cycle = None;
        let mut regwrite_count = 0;
        let mut alu_print_count = 0;
        let mut pc_print_count = 0;

        let mut vcd = { self.model.borrow_mut().open_vcd(vcd_path) };

        for cycle in 0..max_cycles {
            self.tick(&mut Some(&mut vcd));

            let dut = self.model.borrow();

            // Debug: Print PC progression for first 50 cycles and during test 18
            if (cycle < 50 || (dut.debug_pc >= 0x80000330 && dut.debug_pc <= 0x80000360))
                && pc_print_count < 100
            {
                pc_print_count += 1;
                eprintln!(
                    "PC[{}]: cycle {} pc=0x{:08x}",
                    pc_print_count, cycle, dut.debug_pc
                );
            }

            // Debug: Print ALU operations in test 18 range (PC 0x80000334-0x80000350)
            if dut.debug_aluValid != 0
                && dut.debug_aluPc >= 0x80000334
                && dut.debug_aluPc <= 0x80000350
                && alu_print_count < 20
            {
                alu_print_count += 1;
                eprintln!(
                    "ALU[{}]: cycle {} EXE[pc=0x{:08x} rd={} rw={}] MEM[pc=0x{:08x} rd={} rw={}] WB[pc=0x{:08x} rd={} rw={}]",
                    alu_print_count,
                    cycle,
                    dut.debug_aluPc,
                    dut.debug_aluRd,
                    dut.debug_aluRegWrite,
                    dut.debug_memPc,
                    dut.debug_memRd,
                    dut.debug_memRegWrite,
                    dut.debug_wbPc,
                    dut.debug_wbRd,
                    dut.debug_wbRegWrite
                );
            }

            // Debug: Print ALL register writes (including zeros) for first 30
            if dut.debug_regWrite != 0 {
                regwrite_count += 1;
                // Print first 30, cycles 350-400, and all x3 writes
                if regwrite_count <= 30
                    || (cycle >= 350 && cycle <= 400)
                    || dut.debug_writeAddr == 3
                    || matches!(
                        dut.debug_writeAddr,
                        1 | 2 | 4 | 7 | 11 | 12 | 14 | 30
                    )
                {
                    eprintln!(
                        "REGWRITE[{}]: cycle {} x{} = 0x{:08x} wbPc=0x{:08x}",
                        regwrite_count,
                        cycle,
                        dut.debug_writeAddr,
                        dut.debug_writeData,
                        dut.debug_wbPc
                    );
                }
            }

            // Check for test completion by detecting when x3 (gp) is written to 1 (pass)
            // All RISC-V tests write x3=1 in the pass handler
            if completion_detected_cycle.is_none()
                && dut.debug_regWrite != 0
                && dut.debug_writeAddr == 3
                && dut.debug_writeData == 1
            {
                completion_detected_cycle = Some(cycle);
                failure_cycle = Some(cycle);
                eprintln!("TRACE: detected PASS (x3=1) at cycle {}", cycle);
            }

            // Also check for fail by detecting ecall with x3 != 1 after many cycles
            // (This is a fallback - we'll hit max_cycles if the test really fails)
            if completion_detected_cycle.is_none() && cycle > 10000 {
                eprintln!("TRACE: test timeout at cycle {}", cycle);
                break;
            }

            // Break 10 cycles after detecting completion to let pending writes finish
            // (including ECALL extra writes which take 2 cycles)
            if let Some(detected_cycle) = completion_detected_cycle {
                if cycle >= detected_cycle + 10 {
                    break;
                }
            }
        }

        let regs = self.capture_registers()?;
        let exit_code = regs.get(3); // x3/gp holds test result

        Ok(TestResult {
            regs,
            cycles: failure_cycle.unwrap_or(max_cycles),
            exit_code: Some(exit_code),
        })
    }

    fn capture_registers(&self) -> Result<RegisterFile> {
        let mut regs = RegisterFile::new();

        let mut dut = self.model.borrow_mut();

        // Hold clock low while sampling register file (combinational read)
        dut.clock = 0;
        dut.eval();

        dut.regfile_read_en = 1;
        for idx in 0..32 {
            dut.regfile_read_addr = idx as u8;
            dut.eval();
            let val = dut.regfile_read_data;
            regs.set(idx as u8, val);
        }
        dut.regfile_read_en = 0;

        Ok(regs)
    }

    fn write_mem_byte(&self, addr: u32, data: u8) {
        {
            let mut dut = self.model.borrow_mut();
            dut.mem_write_en = 1;
            dut.mem_write_addr = addr;
            dut.mem_write_data = data;
        }
        self.tick(&mut None);
        {
            let mut dut = self.model.borrow_mut();
            dut.mem_write_en = 0;
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
        cycles: 0, // Spike doesn't report cycles in the same way
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
