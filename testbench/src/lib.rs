use std::{
    collections::BTreeMap,
    io::{self, ErrorKind},
    path::Path,
};

use marlin::verilog::prelude::*;

const ROM_BASE: u32 = 0x8000_0000;
const ROM_SIZE_BYTES: u32 = 16 * 1024;
const RAM_BASE: u32 = 0x8000_0000;
const RAM_SIZE_BYTES: u32 = 16 * 1024;

#[verilog(src = "/Users/alex/Projects/svarog/testbench/target/VerilatorTop.sv", name = "VerilatorTop")]
pub struct VerilatorTop;

#[derive(Debug, Clone)]
pub struct MemoryWord {
    pub addr: u32,
    pub data: u32,
    pub mask: u8,
}

/// Load a program image into both ROM and RAM
pub fn load_program(dut: &mut VerilatorTop, image: &[MemoryWord]) {
    let previous_hold = dut.boot_hold;
    dut.boot_hold = 1;

    dut.rom_write_en = 0;
    dut.rom_write_mask = 0;
    dut.ram_write_en = 0;
    dut.ram_write_mask = 0;

    for word in image {
        if word.mask == 0 {
            continue;
        }

        if let Some(idx) = rom_index(word.addr) {
            write_rom_word(dut, idx, word.data, word.mask);
        }

        if let Some(idx) = ram_index(word.addr) {
            write_ram_word(dut, idx, word.data, word.mask);
        }
    }

    dut.rom_write_en = 0;
    dut.ram_write_en = 0;
    dut.eval();
    dut.boot_hold = previous_hold;
}

fn rom_index(addr: u32) -> Option<u32> {
    if addr < ROM_BASE || addr >= ROM_BASE + ROM_SIZE_BYTES {
        return None;
    }
    Some((addr - ROM_BASE) / 4)
}

fn ram_index(addr: u32) -> Option<u32> {
    if addr < RAM_BASE || addr >= RAM_BASE + RAM_SIZE_BYTES {
        return None;
    }
    Some((addr - RAM_BASE) / 4)
}

fn write_rom_word(dut: &mut VerilatorTop, idx: u32, data: u32, mask: u8) {
    dut.rom_write_en = 1;
    dut.rom_write_addr = idx as u16;
    dut.rom_write_data = data;
    dut.rom_write_mask = mask;
    tick(dut);
    dut.rom_write_en = 0;
}

fn write_ram_word(dut: &mut VerilatorTop, idx: u32, data: u32, mask: u8) {
    dut.ram_write_en = 1;
    dut.ram_write_addr = idx as u16;
    dut.ram_write_data = data;
    dut.ram_write_mask = mask;
    tick(dut);
    dut.ram_write_en = 0;
}

fn tick(dut: &mut VerilatorTop) {
    dut.clock = 0;
    dut.eval();
    dut.clock = 1;
    dut.eval();
}

/// Load a .vh memory image produced by riscv-tests. Supports sparse writes.
pub fn load_vh_image(path: impl AsRef<Path>) -> io::Result<Vec<MemoryWord>> {
    let content = std::fs::read_to_string(path)?;
    let mut current_addr: u32 = 0;
    let mut bytes = BTreeMap::<u32, u8>::new();

    for raw in content.split_whitespace() {
        let token = raw.trim();
        if token.is_empty() {
            continue;
        }

        if token.starts_with('@') {
            let addr_str = &token[1..];
            current_addr = u32::from_str_radix(addr_str, 16).map_err(|e| {
                io::Error::new(
                    ErrorKind::InvalidData,
                    format!("Invalid address token `{}`: {}", token, e),
                )
            })?;
            continue;
        }

        if token.len() != 2 {
            return Err(io::Error::new(
                ErrorKind::InvalidData,
                format!("Unexpected token `{}`", token),
            ));
        }

        let byte = u8::from_str_radix(token, 16).map_err(|e| {
            io::Error::new(
                ErrorKind::InvalidData,
                format!("Invalid byte `{}`: {}", token, e),
            )
        })?;

        bytes.insert(current_addr, byte);
        current_addr = current_addr
            .checked_add(1)
            .ok_or_else(|| io::Error::new(ErrorKind::InvalidData, "Address overflow"))?;
    }

    let mut words = BTreeMap::<u32, (u32, u8)>::new();
    for (byte_addr, byte_val) in bytes {
        let word_addr = byte_addr & !0x3;
        let shift = ((byte_addr & 0x3) * 8) as u32;
        let entry = words.entry(word_addr).or_insert((0u32, 0u8));
        entry.0 = (entry.0 & !(0xFF << shift)) | ((byte_val as u32) << shift);
        entry.1 |= 1 << (byte_addr & 0x3);
    }

    Ok(words
        .into_iter()
        .map(|(addr, (data, mask))| MemoryWord { addr, data, mask })
        .collect())
}
