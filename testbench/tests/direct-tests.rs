//! Direct tests runner
//!
//! Runs handwritten assembly tests from testbench/direct-tests/rv32/
//! These tests exercise specific hardware features like CLINT interrupts.

use anyhow::{Context, Result};
use glob::glob;
use libtest_mimic::{Arguments, Failed, Trial};
use std::path::{Path, PathBuf};
use testbench::{Backend, Simulator};

const TARGET_PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../target/");

fn main() -> Result<()> {
    let vcd_path = PathBuf::from(format!("{}/vcd", TARGET_PATH));
    std::fs::create_dir_all(&vcd_path)?;
    let args = Arguments::from_args();

    let tests = discover_tests()?;

    libtest_mimic::run(&args, tests).exit();
}

/// Discover all direct test cases
fn discover_tests() -> Result<Vec<Trial>> {
    let mut trials = Vec::new();

    let models = Simulator::available_models(Backend::VerilatorMonitored);

    // For each model, create tests
    for &model_name in models {
        // Discover built test binaries
        let pattern = format!("{TARGET_PATH}/direct-tests/rv32/*");
        for test_path in glob(&pattern)? {
            let test_path = test_path?;

            // Skip if not a file (e.g., directories)
            if !test_path.is_file() {
                continue;
            }

            // Skip files with extensions (we want the ELF, not .S or .o)
            if test_path.extension().is_some() {
                continue;
            }

            let test_name = test_path.file_name().unwrap().to_str().unwrap().to_owned();

            trials.push(Trial::test(
                format!("{}::{}", model_name, test_name),
                move || run_test(&test_path, model_name),
            ));
        }
    }

    Ok(trials)
}

/// Run a single test case
fn run_test(test_path: &Path, model_name: &'static str) -> Result<(), Failed> {
    match run_test_impl(test_path, model_name) {
        Ok(()) => Ok(()),
        Err(e) => Err(format!("{:#}", e).into()),
    }
}

fn run_test_impl(test_path: &Path, model_name: &'static str) -> Result<()> {
    let test_name = test_path.file_name().unwrap().to_str().unwrap().to_owned();
    let vcd_path = PathBuf::from(format!(
        "{}/vcd/direct_{}_{}.vcd",
        TARGET_PATH, model_name, test_name
    ));

    // Create simulator with specified model
    let simulator = Simulator::new(Backend::VerilatorMonitored, model_name)
        .map_err(|e| anyhow::anyhow!("Failed to create simulator: {}", e))?;

    // Load the ELF binary with watchpoint on 'tohost' symbol
    let _tohost_addr = simulator
        .load_binary(test_path, Some("tohost"))
        .context("Failed to load binary")?;

    // Run simulation with generous cycle limit for interrupt tests
    let max_cycles = std::env::var("SVAROG_MAX_CYCLES")
        .ok()
        .and_then(|val| val.parse::<usize>().ok())
        .unwrap_or(50_000);

    println!("Simulating {} on model {}...", test_name, model_name);
    let result = simulator
        .run(Some(&vcd_path), max_cycles)
        .context("Simulation failed")?;
    println!("Simulation complete");

    // Check test result in gp (x3) register
    // gp = 1 means PASS
    // gp = (test_num << 1 | 1) means FAIL at test_num
    let gp = result.regs.get(3);

    if gp == 1 {
        println!("Test PASSED");
        Ok(())
    } else if gp == 0 {
        anyhow::bail!(
            "Test did not complete (gp=0). Simulation may have timed out or test didn't reach tohost."
        );
    } else {
        let test_num = gp >> 1;
        anyhow::bail!("Test FAILED at test case {} (gp=0x{:08x})", test_num, gp);
    }
}
