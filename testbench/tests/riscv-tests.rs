use anyhow::{Context, Result};
use glob::glob;
use libtest_mimic::{Arguments, Failed, Trial};
use std::path::{Path, PathBuf};
use testbench::{compare_results, run_spike_test, Backend, Simulator};

const TARGET_PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../target/");

fn main() -> Result<()> {
    let vcd_path = PathBuf::from(format!("{}/vcd", TARGET_PATH));
    std::fs::create_dir_all(&vcd_path)?;
    let args = Arguments::from_args();
    // Note: With the cxx-rs integration, Verilator model is built once during
    // cargo build, so tests can now run in parallel if desired.
    // We keep the default test thread count from args.

    let tests = discover_tests()?;

    libtest_mimic::run(&args, tests).exit();
}

/// Discover all test cases based on hex files and top modules
fn discover_tests() -> Result<Vec<Trial>> {
    let mut trials = Vec::new();

    // Get all available models
    let backend = Backend::Verilator;
    let models = Simulator::available_models(backend);

    // For each model, create tests
    for &model_name in models {
        // Use the generated manifest for test discovery
        for test_path in glob(&format!("{TARGET_PATH}/riscv-tests/isa/rv32ui-p-*"))? {
            let test_path = test_path?;
            let test_name = test_path.file_name().unwrap().to_str().unwrap().to_owned();
            if test_name.ends_with(".dump") {
                continue;
            }
            // misaligned unsupported
            if test_name.starts_with("rv32ui-p-ma") {
                continue;
            }
            trials.push(Trial::test(
                format!("{}::{}", model_name, test_name),
                move || run_test(&test_path, backend, model_name),
            ));
        }
    }

    Ok(trials)
}

/// Run a single test case
fn run_test(test_path: &Path, backend: Backend, model_name: &'static str) -> Result<(), Failed> {
    match run_test_impl(test_path, backend, model_name) {
        Ok(()) => Ok(()),
        Err(e) => Err(format!("{:#}", e).into()),
    }
}

fn run_test_impl(test_path: &Path, backend: Backend, model_name: &'static str) -> Result<()> {
    let test_name = test_path.file_name().unwrap().to_str().unwrap().to_owned();
    let vcd_path = PathBuf::from(format!(
        "{}/vcd/{}_{}.vcd",
        TARGET_PATH, model_name, test_name
    ));

    // Create simulator with specified model
    let simulator = Simulator::new(backend, model_name)
        .map_err(|e| anyhow::anyhow!("Failed to create simulator: {}", e))?;

    // Load the ELF binary with watchpoint on 'tohost' symbol
    let tohost_addr = simulator
        .load_binary(test_path, Some("tohost"))
        .context("Failed to load binary")?;

    // Run Verilator simulation
    let max_cycles = std::env::var("SVAROG_MAX_CYCLES")
        .ok()
        .and_then(|val| val.parse::<usize>().ok())
        .unwrap_or(20_000);

    println!("Simulating {} on model {}...", test_name, model_name);
    let verilator_result = simulator
        .run(&vcd_path, max_cycles)
        .context("Verilator simulation failed")?;
    println!("Simulation complete, capturing registers");

    // Check if there was any register activity
    let mut has_activity = false;
    for i in 1..32 {
        if verilator_result.regs.get(i) != 0 {
            has_activity = true;
            break;
        }
    }

    if !has_activity {
        anyhow::bail!(
            "No register writes detected from Verilator. \
            CPU may not be completing writeback stage."
        );
    }

    // Run Spike and compare architectural state
    println!("Running Spike for {}", test_name);
    let spike_result =
        run_spike_test(test_path, tohost_addr, "RV32I").context("Spike simulation failed")?;

    println!("Comparing architectural state");
    compare_results(&verilator_result, &spike_result)?;
    Ok(())
}
