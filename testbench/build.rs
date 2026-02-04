use anyhow::{Context, Result};
use std::path::{Path, PathBuf};
use xshell::{Shell, cmd};

fn main() -> Result<()> {
    let sh = Shell::new()?;

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=direct-tests");

    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);

    sh.change_dir(std::env::var("OUT_DIR").unwrap());

    let cur_dir = sh.current_dir();

    // Build riscv-tests
    let riscv_tests_dir = cur_dir.join("target/riscv-tests");
    let riscv_arch_dir = cur_dir.join("target/riscv-arch-test");

    simtools::clone_repo(
        "https://github.com/riscv-software-src/riscv-tests",
        &riscv_tests_dir,
    )?;
    simtools::clone_repo(
        "https://github.com/riscv-non-isa/riscv-arch-test.git",
        &riscv_arch_dir,
    )?;

    sh.change_dir(&riscv_tests_dir);

    cmd!(sh, "git submodule update --init --recursive")
        .run()
        .context("Failed to initialize submodules")?;

    let patch_file = manifest_dir.join("patches/riscv-tests-no-priv.patch");
    cmd!(
        sh,
        "git apply --ignore-space-change --ignore-whitespace {patch_file}"
    )
    .run()
    .context("Failed to patch riscv-tests with simplified env")?;

    cmd!(sh, "autoconf")
        .run()
        .context("Failed to run autoconf")?;

    let prefix = riscv_tests_dir.join("install");
    cmd!(sh, "./configure --prefix={prefix} --with-xlen=32")
        .run()
        .context("Failed to configure riscv-tests")?;

    let riscv_prefix = "riscv32-unknown-elf-";

    // Build rv32ui tests
    sh.change_dir(riscv_tests_dir.join("isa"));

    // Build all rv32ui tests
    cmd!(sh, "make -j XLEN=32 RISCV_PREFIX={riscv_prefix} rv32ui")
        .run()
        .context("Failed to build rv32ui test suite")?;

    // Build riscv-arch-test suite (rv32i_m/I and rv32i_m/M)
    build_riscv_arch_tests(&sh, &riscv_arch_dir)?;

    // Build direct tests
    sh.change_dir(&cur_dir);
    build_direct_tests(&sh, &manifest_dir)?;

    Ok(())
}

/// Build handwritten assembly tests from testbench/direct-tests/
fn build_direct_tests(sh: &Shell, workspace_dir: &Path) -> Result<()> {
    let direct_tests_src = workspace_dir.join("direct-tests/rv32");
    let direct_tests_out =
        PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("target/direct-tests/rv32");
    let common_dir = direct_tests_src.join("common");

    // Create output directory
    std::fs::create_dir_all(&direct_tests_out)?;

    let crt0 = common_dir.join("crt0.S");
    let linker_script = common_dir.join("linker.ld");

    // Find all .S files in direct-tests/rv32 (excluding common/)
    for entry in std::fs::read_dir(&direct_tests_src)? {
        let entry = entry?;
        let path = entry.path();

        // Skip directories and non-.S files
        if path.is_dir() {
            continue;
        }
        if path.extension().and_then(|e| e.to_str()) != Some("S") {
            continue;
        }

        let test_name = path.file_stem().unwrap().to_str().unwrap();
        let output_elf = direct_tests_out.join(test_name);

        cmd!(
            sh,
            "riscv32-unknown-elf-gcc -march=rv32i_zicsr_zicntr -mabi=ilp32 -nostdlib -nostartfiles -static -T {linker_script} -o {output_elf} {crt0} {path}"
        )
        .run()
        .with_context(|| format!("Failed to build direct test: {}", test_name))?;
    }

    Ok(())
}

fn build_riscv_arch_tests(sh: &Shell, riscv_arch_dir: &Path) -> Result<()> {
    let suite_dir = riscv_arch_dir.join("riscv-test-suite");
    let env_dir = suite_dir.join("env");
    let model_env_dir = riscv_arch_dir.join("riscof-plugins/rv32/spike_simple/env");
    let link_ld = model_env_dir.join("link.ld");
    let arch_header = env_dir.join("arch_test.h");

    if !suite_dir.exists() {
        anyhow::bail!("Missing riscv-arch-test suite at {:?}", suite_dir);
    }

    sh.change_dir(riscv_arch_dir);

    // Read the arch_test.h file
    let arch_test_content =
        std::fs::read_to_string(&arch_header).context("Failed to read arch_test.h")?;

    // Replace all .option rvc with .option norvc
    let patched_content = arch_test_content.replace(".option rvc", ".option norvc");

    // Write back the patched file
    std::fs::write(&arch_header, patched_content).context("Failed to write patched arch_test.h")?;

    let suites = [("I", "rv32i_zicsr"), ("M", "rv32im_zicsr")];
    for (suite, march) in suites {
        let src_dir = suite_dir.join(format!("rv32i_m/{suite}/src"));
        let out_dir = riscv_arch_dir.join(format!("rv32i_m/{suite}"));
        std::fs::create_dir_all(&out_dir)?;

        for entry in std::fs::read_dir(&src_dir)? {
            let entry = entry?;
            let path = entry.path();
            if path.extension().and_then(|ext| ext.to_str()) != Some("S") {
                continue;
            }

            let test_name = path.file_stem().unwrap().to_str().unwrap();
            let output_elf = out_dir.join(format!("{test_name}.elf"));

            cmd!(
                sh,
                "riscv32-unknown-elf-gcc -g -static -mcmodel=medany -fvisibility=hidden -march={march} -mabi=ilp32 -nostdlib -nostartfiles -DXLEN=32 -DTEST_CASE_1 -I {env_dir} -I {model_env_dir} -T {link_ld} -o {output_elf} {path}"
            )
            .run()
            .with_context(|| format!("Failed to build riscv-arch-test {suite}: {test_name}"))?;
        }
    }

    Ok(())
}
