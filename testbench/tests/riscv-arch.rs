use anyhow::{Context, Result};
use glob::glob;
use libtest_mimic::{Arguments, Failed, Trial};
use std::path::{Path, PathBuf};
use testbench::{Backend, Simulator, compare_results, run_spike_test};

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

    let backend = Backend::Verilator;
    let models = Simulator::available_models(backend);
    let suites = ["I", "M"];

    // Maximum binary size that can fit in RAM (64KB = 65536 bytes)
    const MAX_BINARY_SIZE: u64 = 64 * 1024;

    for &model_name in models {
        for suite in suites {
            let pattern = format!("{TARGET_PATH}/riscv-arch-test/rv32i_m/{suite}/*.elf");
            for test_path in glob(&pattern)? {
                let test_path = test_path?;
                if !test_path.is_file() {
                    continue;
                }
                let test_name = test_path.file_stem().unwrap().to_str().unwrap().to_owned();
                let suite_name = suite.to_owned();

                // Check if binary is too large
                let file_size = std::fs::metadata(&test_path)
                    .context("Failed to get file metadata")?
                    .len();

                if file_size > MAX_BINARY_SIZE {
                    // Create an ignored test with a reason
                    trials.push(
                        Trial::test(
                            format!("{}::arch::{}::{}", model_name, suite, test_name),
                            || Ok(()),
                        )
                        .with_ignored_flag(true)
                        .with_kind(format!(
                            "binary too large: {} bytes (max {})",
                            file_size, MAX_BINARY_SIZE
                        )),
                    );
                } else if test_name.contains("rem") || test_name.contains("div") {
                    trials.push(
                        Trial::test(
                            format!("{}::arch::{}::{}", model_name, suite, test_name),
                            || Ok(()),
                        )
                        .with_ignored_flag(true)
                        .with_kind("Division is not synthesizable for now"),
                    );
                } else {
                    trials.push(Trial::test(
                        format!("{}::arch::{}::{}", model_name, suite, test_name),
                        move || run_test(&test_path, backend, model_name, &suite_name),
                    ));
                }
            }
        }
    }

    Ok(trials)
}

/// Run a single test case.
fn run_test(
    test_path: &Path,
    backend: Backend,
    model_name: &'static str,
    suite: &str,
) -> Result<(), Failed> {
    match run_test_impl(test_path, backend, model_name, suite) {
        Ok(()) => Ok(()),
        Err(e) => Err(format!("{:#}", e).into()),
    }
}

fn run_test_impl(
    test_path: &Path,
    backend: Backend,
    model_name: &'static str,
    suite: &str,
) -> Result<()> {
    let test_name = test_path.file_stem().unwrap().to_str().unwrap().to_owned();
    let vcd_path = PathBuf::from(format!(
        "{}/vcd/arch_{}_{}_{}.vcd",
        TARGET_PATH, model_name, suite, test_name
    ));

    let simulator = Simulator::new(backend, model_name)
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
        .run(Some(&vcd_path), max_cycles)
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
