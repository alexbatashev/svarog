//! RISC-V CPU Test Harness
//!
//! This test harness cross-validates the Verilator simulation against Spike.
//!
//! ## Adding New Top Modules
//!
//! To test different CPU configurations:
//! 1. Add a new struct in src/lib.rs with the #[verilog] macro
//! 2. Add the module to the `top_modules` vector in main()
//! 3. Add a match arm in run_verilator_test() to handle the new module
//!
//! Example:
//! ```ignore
//! let top_modules = vec![
//!     TopModule { name: "VerilatorTop" },
//!     TopModule { name: "MyCustomTop" },
//! ];
//! ```

use anyhow::{Context, Result};
use camino::Utf8PathBuf;
use libtest_mimic::{Arguments, Failed, Trial};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use xshell::{cmd, Shell};

// Include the generated test manifest
include!("../tests/manifest.rs");

/// Represents a Top module configuration for testing
#[derive(Clone)]
struct TopModule {
    name: &'static str,
}

/// Register file state (x0-x31)
#[derive(Debug, Clone, PartialEq, Eq)]
struct RegisterFile {
    regs: [u32; 32],
}

impl RegisterFile {
    fn new() -> Self {
        Self { regs: [0; 32] }
    }

    fn set(&mut self, idx: u8, value: u32) {
        if idx < 32 {
            self.regs[idx as usize] = value;
        }
    }

    fn get(&self, idx: u8) -> u32 {
        if idx < 32 {
            self.regs[idx as usize]
        } else {
            0
        }
    }
}

fn main() -> Result<()> {
    let args = Arguments::from_args();

    // Discover all available top modules
    let top_modules = vec![TopModule {
        name: "VerilatorTop",
    }];

    // Discover tests
    let tests = discover_tests(&top_modules)?;

    // Run tests
    libtest_mimic::run(&args, tests).exit();
}

/// Discover all test cases based on hex files and top modules
fn discover_tests(top_modules: &[TopModule]) -> Result<Vec<Trial>> {
    let mut trials = Vec::new();

    // Use the generated manifest for test discovery
    for test_name in RV32UI_TESTS {
        for top in top_modules {
            let test_name = test_name.to_string();
            let top = top.clone();

            trials.push(Trial::test(
                format!("{}::{}", top.name, test_name),
                move || run_test(&top, &test_name),
            ));
        }
    }

    Ok(trials)
}

/// Run a single test case
fn run_test(top: &TopModule, test_name: &str) -> Result<(), Failed> {
    match run_test_impl(top, test_name) {
        Ok(()) => Ok(()),
        Err(e) => Err(format!("{:#}", e).into()),
    }
}

fn run_test_impl(top: &TopModule, test_name: &str) -> Result<()> {
    let workspace_root = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let vh_path = workspace_root.join("tests").join(format!("{}.vh", test_name));
    let elf_path = workspace_root
        .join("riscv-tests/isa")
        .join(test_name);

    // Run Verilator simulation
    let verilator_result = run_verilator_test(top, &vh_path)
        .context("Verilator simulation failed")?;

    if !has_register_activity(&verilator_result) {
        anyhow::bail!(
            "No register writes detected from Verilator. \
            CPU may not be completing writeback stage. \
            Check that writeback.io.regFile.writeEn is being asserted."
        );
    }

    // Run Spike and compare architectural state
    let spike_result = run_spike_test(&elf_path)
        .context("Spike simulation failed")?;

    compare_results(&verilator_result, &spike_result)?;
    Ok(())
}

fn has_register_activity(result: &TestResult) -> bool {
    for i in 1..32 {
        if result.regs.get(i) != 0 {
            return true;
        }
    }
    false
}

fn capture_registers(dut: &mut testbench::VerilatorTop) -> RegisterFile {
    let mut regs = RegisterFile::new();

    // Hold clock low while sampling register file
    dut.clock = 0;
    dut.eval();

    dut.regfile_read_en = 1;
    for idx in 0..32 {
        dut.regfile_read_addr = idx as u8;
        dut.eval();
        regs.set(idx as u8, dut.regfile_read_data);
    }
    dut.regfile_read_en = 0;
    regs
}

/// Run test in Verilator and return register state
fn run_verilator_test(top: &TopModule, vh_path: &Path) -> Result<TestResult> {
    match top.name {
        "VerilatorTop" => run_verilator_top_test(vh_path),
        _ => anyhow::bail!("Unknown top module: {}", top.name),
    }
}

fn run_verilator_top_test(vh_path: &Path) -> Result<TestResult> {
    use marlin::verilator::{VerilatorRuntime, VerilatorRuntimeOptions};

    // Load the memory image
    let program = testbench::load_vh_image(vh_path)
        .context("Failed to load memory image")?;

    // Create Verilator runtime
    let workspace_root = Utf8PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let verilog_src = workspace_root.join("target/VerilatorTop.sv");
    let build_dir = workspace_root.join("target/verilator_build");

    let runtime = VerilatorRuntime::new(
        &build_dir,
        &[verilog_src.as_ref()],
        &[],
        [],
        VerilatorRuntimeOptions::default(),
    )
    .map_err(|e| anyhow::anyhow!("Failed to create VerilatorRuntime: {}", e))?;

    // Create DUT
    let mut dut = runtime
        .create_model_simple::<testbench::VerilatorTop>()
        .map_err(|e| anyhow::anyhow!("Failed to create model: {}", e))?;

    // Hold CPU while loading program
    dut.reset = 1;
    dut.boot_hold = 1;
    dut.regfile_read_en = 0;
    for _ in 0..5 {
        dut.clock = 0;
        dut.eval();
        dut.clock = 1;
        dut.eval();
    }
    dut.reset = 0;

    // Load program into ROM while CPU is held
    testbench::load_program(&mut dut, &program);

    // Release hold so CPU can start executing
    dut.boot_hold = 0;

    // Run simulation
    let max_cycles = 100_000;
    let mut failure_cycle = None;
    let mut branch_dumped = false;
    let mut branch_test3_dumped = false;
    let mut branch_trace_printed = 0u32;
    let mut tp_loop_count = 0u32;
    for cycle in 0..max_cycles {
        dut.clock = 0;
        dut.eval();
        dut.clock = 1;
        dut.eval();

        if cycle < 400 {
            let pc = dut.debug_pc;
            let reg_write = dut.debug_regWrite;
            let write_addr = dut.debug_writeAddr;
            let write_data = dut.debug_writeData;
            eprintln!(
                "TRACE: cycle {:04} pc=0x{:08x} regWrite={} rd={} data=0x{:08x}",
                cycle, pc, reg_write, write_addr, write_data
            );
        }

        if branch_trace_printed < 128 && dut.debug_branchValid != 0 {
            branch_trace_printed += 1;
            eprintln!(
                "DEBUG: branch_signal pc=0x{:08x} rs1=0x{:08x} rs2=0x{:08x} taken={}",
                dut.debug_branchPc,
                dut.debug_branchRs1,
                dut.debug_branchRs2,
                dut.debug_branchTaken
            );
        }

        if dut.watch_decode_hit != 0 {
            eprintln!(
                "WATCH: decode valid={} rd=x{} regWrite={} opType={} imm=0x{:08x} reqValid={} respValid={} stall={}",
                dut.watch_decode_valid,
                dut.watch_decode_rd,
                dut.watch_decode_regWrite,
                dut.watch_decode_opType,
                dut.watch_decode_imm,
                dut.watch_decode_reqValid,
                dut.watch_decode_respValid,
                dut.watch_frontend_stall,
            );
        }
        if dut.watch_execute_hit != 0 {
            eprintln!(
                "WATCH: execute valid={} rd=x{} regWrite={} opType={} intResult=0x{:08x}",
                dut.watch_execute_valid,
                dut.watch_execute_rd,
                dut.watch_execute_regWrite,
                dut.watch_execute_opType,
                dut.watch_execute_intResult,
            );
        }
        if dut.watch_memory_hit != 0 {
            eprintln!(
                "WATCH: memory rd=x{} regWrite={} intResult=0x{:08x}",
                dut.watch_memory_rd,
                dut.watch_memory_regWrite,
                dut.watch_memory_intResult,
            );
        }
        if dut.watch_writeback_hit != 0 {
            eprintln!(
                "WATCH: writeback rd=x{} regWrite={} result=0x{:08x}",
                dut.watch_writeback_rd,
                dut.watch_writeback_regWrite,
                dut.watch_writeback_result,
            );
        }

        if !branch_dumped && dut.debug_pc == 0x8000_01a0 {
            branch_dumped = true;
            let regs = capture_registers(&mut dut);
            eprintln!(
                "DEBUG: branch @0x800001a0 a4(x14)=0x{:08x} t2(x7)=0x{:08x} gp(x3)=0x{:08x}",
                regs.get(14),
                regs.get(7),
                regs.get(3),
            );
        }
        if !branch_test3_dumped && dut.debug_branchValid != 0 && dut.debug_branchPc == 0x8000_01b4 {
            branch_test3_dumped = true;
            let regs = capture_registers(&mut dut);
            eprintln!(
                "DEBUG: branch @0x800001b8 a4(x14)=0x{:08x} t2(x7)=0x{:08x} gp(x3)=0x{:08x}",
                regs.get(14),
                regs.get(7),
                regs.get(3),
            );
        }
        if dut.debug_pc == 0x8000_0388 && tp_loop_count < 4 {
            tp_loop_count += 1;
            let regs = capture_registers(&mut dut);
            eprintln!(
                "DEBUG: branch @0x80000388 tp(x4)=0x{:08x} t0(x5)=0x{:08x} gp(x3)=0x{:08x} iter={}",
                regs.get(4),
                regs.get(5),
                regs.get(3),
                tp_loop_count,
            );
        }

        if failure_cycle.is_none() && dut.debug_pc == 0x8000_066c {
            failure_cycle = Some(cycle);
            eprintln!("TRACE: detected fail handler at cycle {}", cycle);
            break;
        }
    }

    let regs = capture_registers(&mut dut);
    let exit_code = regs.get(3); // x3/gp holds test result

    Ok(TestResult {
        regs,
        cycles: max_cycles,
        exit_code: Some(exit_code),
    })
}

/// Run test in Spike and return register state
fn run_spike_test(elf_path: &Path) -> Result<TestResult> {
    let sh = Shell::new()?;

    // Run spike with register dumping
    // -l enables commit log, -d enables debug mode
    let output = cmd!(
        sh,
        "spike --isa=RV32I -l --log-commits {elf_path}"
    )
    .ignore_status()
    .output()
    .context("Failed to run spike")?;

    // Parse spike output to extract register state
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);

    eprintln!("DEBUG: Spike stdout length: {}", stdout.len());
    eprintln!("DEBUG: Spike stderr length: {}", stderr.len());
    eprintln!("DEBUG: Spike stdout first 500 chars:\n{}", &stdout.chars().take(500).collect::<String>());
    eprintln!("DEBUG: Spike stderr first 500 chars:\n{}", &stderr.chars().take(500).collect::<String>());

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

    // Spike outputs register writes in the format:
    // core   0: 0x........  ........ x<reg> 0x<value>
    // We need to parse the last write to each register

    let mut reg_map: HashMap<u8, u32> = HashMap::new();

    for line in stdout.lines().chain(stderr.lines()) {
        // Look for lines with register writes
        // Format: "core   0: 0x80000000 (0x00000297) x 5 0x80000000"
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
    // Look for pattern: x<num> 0x<hex>
    let parts: Vec<&str> = line.split_whitespace().collect();
    let mut i = 0;

    while i < parts.len() {
        let part = parts[i];

        // Case 1: token is exactly "x" and next token is the register number
        if part == "x" && i + 2 < parts.len() {
            if let (Ok(reg_num), Some(value)) = (
                parts[i + 1].parse::<u8>(),
                parse_hex(parts[i + 2]),
            ) {
                return Some((reg_num, value));
            }
        }

        // Case 2: token looks like "x5"
        if let Some(reg_str) = part.strip_prefix('x') {
            if let (Ok(reg_num), Some(value)) = (
                reg_str.parse::<u8>(),
                parts
                    .get(i + 1)
                    .and_then(|token| parse_hex(token)),
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

/// Test result containing register state
struct TestResult {
    regs: RegisterFile,
    #[allow(dead_code)]
    cycles: usize,
    #[allow(dead_code)]
    exit_code: Option<u32>,
}

/// Compare Verilator and Spike results
fn compare_results(verilator: &TestResult, spike: &TestResult) -> Result<()> {
    // Debug: Print some register values to verify we're actually getting data
    let mut has_nonzero_verilator = false;
    let mut has_nonzero_spike = false;

    for i in 1..32 {
        if verilator.regs.get(i) != 0 {
            has_nonzero_verilator = true;
        }
        if spike.regs.get(i) != 0 {
            has_nonzero_spike = true;
        }
    }

    eprintln!("DEBUG: Verilator has non-zero registers: {}", has_nonzero_verilator);
    eprintln!("DEBUG: Spike has non-zero registers: {}", has_nonzero_spike);
    eprintln!("DEBUG: Verilator x1: 0x{:08x}, Spike x1: 0x{:08x}",
              verilator.regs.get(1), spike.regs.get(1));
    eprintln!("DEBUG: Verilator x10: 0x{:08x}, Spike x10: 0x{:08x}",
              verilator.regs.get(10), spike.regs.get(10));

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
            "Register mismatches:\n{}",
            mismatches.join("\n")
        );
    }

    // TODO: Implement proper RISC-V test completion detection via tohost
    // For now, we just check that registers match
    // The exit code check is disabled because we need to monitor tohost writes

    Ok(())
}
