use anyhow::{Context, Result};
use glob::glob;
use libtest_mimic::{Arguments, Failed, Trial};
use std::path::{Path, PathBuf};
use testbench::{compare_results, run_spike_test, ModelId, Simulator};

const TARGET_PATH: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/../target/");

fn main() -> Result<()> {
    let vcd_path = PathBuf::from(format!("{}/vcd", TARGET_PATH));
    std::fs::create_dir_all(&vcd_path)?;
    let args = Arguments::from_args();

    let tests = discover_tests()?;

    libtest_mimic::run(&args, tests).exit();
}

/// Discover all test cases based on built ELF files.
fn discover_tests() -> Result<Vec<Trial>> {
    let mut trials = Vec::new();

    let models = Simulator::available_models();
    let suites = ["I", "M"];

    for &model_id in models {
        let model_name = model_id.name();

        for suite in suites {
            let pattern = format!("{TARGET_PATH}/riscv-arch-test/rv32i_m/{suite}/*.elf");
            for test_path in glob(&pattern)? {
                let test_path = test_path?;
                if !test_path.is_file() {
                    continue;
                }
                let test_name = test_path.file_stem().unwrap().to_str().unwrap().to_owned();
                let suite_name = suite.to_owned();
                trials.push(Trial::test(
                    format!("{}::arch::{}::{}", model_name, suite, test_name),
                    move || run_test(&test_path, model_id, &suite_name),
                ));
            }
        }
    }

    Ok(trials)
}

/// Run a single test case.
fn run_test(test_path: &Path, model_id: ModelId, suite: &str) -> Result<(), Failed> {
    match run_test_impl(test_path, model_id, suite) {
        Ok(()) => Ok(()),
        Err(e) => Err(format!("{:#}", e).into()),
    }
}

fn run_test_impl(test_path: &Path, model_id: ModelId, suite: &str) -> Result<()> {
    let test_name = test_path.file_stem().unwrap().to_str().unwrap().to_owned();
    let model_name = model_id.name();
    let vcd_path = PathBuf::from(format!(
        "{}/vcd/arch_{}_{}_{}.vcd",
        TARGET_PATH, model_name, suite, test_name
    ));

    let simulator = Simulator::new(model_id)
        .map_err(|e| anyhow::anyhow!("Failed to create simulator: {}", e))?;

    let tohost_addr = simulator
        .load_binary(test_path, Some("tohost"))
        .context("Failed to load binary")?;

    let max_cycles = std::env::var("SVAROG_MAX_CYCLES")
        .ok()
        .and_then(|val| val.parse::<usize>().ok())
        .unwrap_or(50_000);

    println!("Simulating {} on model {}...", test_name, model_name);
    let verilator_result = simulator
        .run(&vcd_path, max_cycles)
        .context("Verilator simulation failed")?;
    println!("Simulation complete, capturing registers");

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

    let isa = if suite == "M" { "RV32IM" } else { "RV32I" };
    println!("Running Spike for {}", test_name);
    let spike_result =
        run_spike_test(test_path, tohost_addr, isa).context("Spike simulation failed")?;

    println!("Comparing architectural state");
    compare_results(&verilator_result, &spike_result)?;
    Ok(())
}
