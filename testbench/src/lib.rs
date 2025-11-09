use std::{cell::RefCell, collections::HashMap, path::Path, process::Command};

use anyhow::{Context, Result};
use elf::{endian::AnyEndian, ElfBytes};
use marlin::{
    verilator::{AsVerilatedModel, VerilatorRuntime, VerilatorRuntimeOptions},
    verilog::prelude::*,
};
use snafu::Whatever;

// const TARGET_PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "../target/");

const ROM_BASE: u32 = 0x8000_0000;
const ROM_SIZE_BYTES: u32 = 16 * 1024;
const RAM_BASE: u32 = 0x8000_0000;
const RAM_SIZE_BYTES: u32 = 16 * 1024;

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
        if idx < 32 {
            self.regs[idx as usize]
        } else {
            0
        }
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
    model: RefCell<VerilatorTop<'static>>,
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

        let model = RefCell::new(runtime.create_model_simple::<VerilatorTop<'static>>()?);
        Ok(Simulator { runtime, model })
    }

    pub fn load_binary<P: AsRef<Path>>(&self, path: P) -> anyhow::Result<()> {
        let file_data = std::fs::read(path)?;
        let slice = file_data.as_slice();
        let file = ElfBytes::<AnyEndian>::minimal_parse(slice)?;

        let text_hdr = file.section_header_by_name(".text.init")?.unwrap();

        let (data, _) = file.section_data(&text_hdr)?;
        let data: &[u32] = bytemuck::cast_slice(data);

        let start_addr = text_hdr.sh_addr as u32;

        {
            let mut dut = self.model.borrow_mut();
            dut.boot_hold = 1;
            dut.rom_write_en = 0;
            dut.rom_write_mask = 0;
            dut.ram_write_en = 0;
            dut.ram_write_mask = 0;
        }

        // Write data word by word, converting address to ROM index
        eprintln!("Loading {} words starting at address 0x{:08x}", data.len(), start_addr);
        for (i, word) in data.iter().enumerate() {
            let addr = start_addr + (i as u32 * 4);
            // Convert absolute address to ROM index (word offset from ROM_BASE)
            let rom_idx = (addr - ROM_BASE) / 4;
            if i < 10 {
                eprintln!("  ROM[{}] = 0x{:08x} (addr 0x{:08x})", rom_idx, word, addr);
            }
            self.write_rom_word(rom_idx, word, 0xF);
        }

        // Release boot_hold after loading (CPU will stay in hold during run() reset sequence)
        {
            let mut dut = self.model.borrow_mut();
            dut.boot_hold = 0;
        }

        Ok(())
    }

    pub fn run(&self, max_cycles: usize) -> Result<TestResult> {
        // Initialize the DUT
        {
            let mut dut = self.model.borrow_mut();
            dut.reset = 1;
            dut.boot_hold = 1;
            dut.regfile_read_en = 0;
            // Make sure ROM write is disabled
            dut.rom_write_en = 0;
            dut.ram_write_en = 0;
        }

        // Reset for a few cycles
        for _ in 0..5 {
            self.tick();
        }

        // Release reset first
        {
            let mut dut = self.model.borrow_mut();
            dut.reset = 0;
        }

        // Wait a cycle, then release boot_hold
        self.tick();

        {
            let mut dut = self.model.borrow_mut();
            dut.boot_hold = 0;
        }

        // Run simulation
        let mut failure_cycle = None;
        let mut regwrite_count = 0;
        for cycle in 0..max_cycles {
            self.tick();

            let dut = self.model.borrow();

            // Debug: Print ALL register writes (including zeros) for first 30
            if dut.debug_regWrite != 0 {
                regwrite_count += 1;
                if regwrite_count <= 30 {
                    eprintln!(
                        "REGWRITE[{}]: cycle {} x{} = 0x{:08x}",
                        regwrite_count, cycle, dut.debug_writeAddr, dut.debug_writeData
                    );
                }
            }

            // Check for test completion at fail handler address
            if failure_cycle.is_none() && dut.debug_pc == 0x8000_066c {
                failure_cycle = Some(cycle);
                eprintln!("TRACE: detected fail handler at cycle {}", cycle);
                break;
            }

            // Optional: Early debug trace (disabled for cleaner output)
            // if cycle < 100 {
            //     eprintln!(
            //         "TRACE: cycle {:04} pc=0x{:08x}",
            //         cycle, dut.debug_pc
            //     );
            // }
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

    fn write_rom_word(&self, rom_idx: u32, data: &u32, mask: u8) {
        {
            let mut dut = self.model.borrow_mut();
            dut.rom_write_en = 1;
            dut.rom_write_addr = rom_idx as u16;
            dut.rom_write_data = *data;
            dut.rom_write_mask = mask;
        }
        self.tick();
        {
            let mut dut = self.model.borrow_mut();
            dut.rom_write_en = 0;
        }
    }

    fn tick(&self) {
        let mut dut = self.model.borrow_mut();
        dut.clock = 0;
        dut.eval();
        dut.clock = 1;
        dut.eval();
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
        anyhow::bail!("Register mismatches:\n{}", mismatches.join("\n"));
    }

    Ok(())
}

// #[derive(Debug, Clone)]
// pub struct MemoryWord {
//     pub addr: u32,
//     pub data: u32,
//     pub mask: u8,
// }

// /// Load a program image into both ROM and RAM
// pub fn load_program(dut: &mut VerilatorTop, image: &[MemoryWord]) {
//     let previous_hold = dut.boot_hold;
//     dut.boot_hold = 1;

//     dut.rom_write_en = 0;
//     dut.rom_write_mask = 0;
//     dut.ram_write_en = 0;
//     dut.ram_write_mask = 0;

//     for word in image {
//         if word.mask == 0 {
//             continue;
//         }

//         if let Some(idx) = rom_index(word.addr) {
//             write_rom_word(dut, idx, word.data, word.mask);
//         }

//         if let Some(idx) = ram_index(word.addr) {
//             write_ram_word(dut, idx, word.data, word.mask);
//         }
//     }

//     dut.rom_write_en = 0;
//     dut.ram_write_en = 0;
//     dut.eval();
//     dut.boot_hold = previous_hold;
// }

// fn rom_index(addr: u32) -> Option<u32> {
//     if addr < ROM_BASE || addr >= ROM_BASE + ROM_SIZE_BYTES {
//         return None;
//     }
//     Some((addr - ROM_BASE) / 4)
// }

// fn ram_index(addr: u32) -> Option<u32> {
//     if addr < RAM_BASE || addr >= RAM_BASE + RAM_SIZE_BYTES {
//         return None;
//     }
//     Some((addr - RAM_BASE) / 4)
// }

// fn write_ram_word(dut: &mut VerilatorTop, idx: u32, data: u32, mask: u8) {
//     dut.ram_write_en = 1;
//     dut.ram_write_addr = idx as u16;
//     dut.ram_write_data = data;
//     dut.ram_write_mask = mask;
//     tick(dut);
//     dut.ram_write_en = 0;
// }

// fn tick(dut: &mut VerilatorTop) {
//     dut.clock = 0;
//     dut.eval();
//     dut.clock = 1;
//     dut.eval();
// }

// /// Load a .vh memory image produced by riscv-tests. Supports sparse writes.
// pub fn load_vh_image(path: impl AsRef<Path>) -> io::Result<Vec<MemoryWord>> {
//     let content = std::fs::read_to_string(path)?;
//     let mut current_addr: u32 = 0;
//     let mut bytes = BTreeMap::<u32, u8>::new();

//     for raw in content.split_whitespace() {
//         let token = raw.trim();
//         if token.is_empty() {
//             continue;
//         }

//         if token.starts_with('@') {
//             let addr_str = &token[1..];
//             current_addr = u32::from_str_radix(addr_str, 16).map_err(|e| {
//                 io::Error::new(
//                     ErrorKind::InvalidData,
//                     format!("Invalid address token `{}`: {}", token, e),
//                 )
//             })?;
//             continue;
//         }

//         if token.len() != 2 {
//             return Err(io::Error::new(
//                 ErrorKind::InvalidData,
//                 format!("Unexpected token `{}`", token),
//             ));
//         }

//         let byte = u8::from_str_radix(token, 16).map_err(|e| {
//             io::Error::new(
//                 ErrorKind::InvalidData,
//                 format!("Invalid byte `{}`: {}", token, e),
//             )
//         })?;

//         bytes.insert(current_addr, byte);
//         current_addr = current_addr
//             .checked_add(1)
//             .ok_or_else(|| io::Error::new(ErrorKind::InvalidData, "Address overflow"))?;
//     }

//     let mut words = BTreeMap::<u32, (u32, u8)>::new();
//     for (byte_addr, byte_val) in bytes {
//         let word_addr = byte_addr & !0x3;
//         let shift = ((byte_addr & 0x3) * 8) as u32;
//         let entry = words.entry(word_addr).or_insert((0u32, 0u8));
//         entry.0 = (entry.0 & !(0xFF << shift)) | ((byte_val as u32) << shift);
//         entry.1 |= 1 << (byte_addr & 0x3);
//     }

//     Ok(words
//         .into_iter()
//         .map(|(addr, (data, mask))| MemoryWord { addr, data, mask })
//         .collect())
// }
