use anyhow::{Context, Result};
use glob::glob;
use libtest_mimic::{Arguments, Failed, Trial};
use std::path::{Path, PathBuf};
use testbench::{Simulator, compare_results, run_spike_test};

const TARGET_PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../target/");

fn main() -> Result<()> {
    let vcd_path = PathBuf::from(format!("{}/vcd", TARGET_PATH));
    std::fs::create_dir_all(&vcd_path)?;
    let args = Arguments::from_args();

    let tests = discover_tests()?;

    libtest_mimic::run(&args, tests).exit();
}

/// Discover all test cases based on hex files and top modules
fn discover_tests() -> Result<Vec<Trial>> {
    let mut trials = Vec::new();

    // Use the generated manifest for test discovery
    for test_path in glob(&format!("{TARGET_PATH}/riscv-tests/isa/rv32ui-p-*"))? {
        let test_path = test_path?;
        let test_name = test_path.file_name().unwrap().to_str().unwrap().to_owned();
        if test_name.ends_with(".dump") {
            continue;
        }

        trials.push(Trial::test(
            format!("svarog-micro::{}", test_name),
            move || run_test(&test_path),
        ));
    }

    Ok(trials)
}

/// Run a single test case
fn run_test(test_path: &Path) -> Result<(), Failed> {
    match run_test_impl(test_path) {
        Ok(()) => Ok(()),
        Err(e) => Err(format!("{:#}", e).into()),
    }
}

fn run_test_impl(test_path: &Path) -> Result<()> {
    let test_name = test_path.file_name().unwrap().to_str().unwrap().to_owned();
    let vcd_path = PathBuf::from(format!("{}/vcd/{}.vcd", TARGET_PATH, test_name));
    // Use a shared build directory for all tests to avoid rebuilding Verilator for each test
    let build_dir = format!("{}/verilator_build", env!("CARGO_TARGET_TMPDIR"));
    let simulator = Simulator::new(&build_dir)
        .map_err(|e| anyhow::anyhow!("Failed to create simulator: {}", e))?;

    // Load the ELF binary
    simulator
        .load_binary(test_path)
        .context("Failed to load binary")?;

    // Run Verilator simulation
    let verilator_result = simulator
        .run(&vcd_path, 100_000)
        .context("Verilator simulation failed")?;

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
    let spike_result = run_spike_test(test_path).context("Spike simulation failed")?;

    compare_results(&verilator_result, &spike_result)?;
    Ok(())
}
