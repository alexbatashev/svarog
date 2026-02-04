use std::{
    io::{BufRead, BufReader},
    path::Path,
    process::{Command, Stdio},
};

use anyhow::{Context, Result};

// Re-export simulator types
pub use simulator::{Backend, RegisterFile, Simulator, TestResult};

/// Run test in Spike and return register state
pub fn run_spike_test(
    elf_path: &Path,
    watchpoint_addr: Option<u32>,
    isa: &str,
) -> Result<TestResult> {
    let mut child = Command::new("spike")
        .arg(format!("--isa={isa}"))
        .args(["-l", "--log-commits"])
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
