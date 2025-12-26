use anyhow::{Context, Result};
use xshell::{Shell, cmd};

fn main() -> Result<()> {
    let sh = Shell::new()?;

    println!("cargo:rerun-if-changed=build.rs");

    // TODO is there a better way to get workspace dir?
    sh.change_dir("..");

    let cur_dir = sh.current_dir();

    // Build riscv-tests
    let riscv_tests_dir = cur_dir.join("target/riscv-tests");

    if !riscv_tests_dir.join("configure.ac").exists() {
        if riscv_tests_dir.exists() {
            std::fs::remove_dir_all(&riscv_tests_dir)?;
        }
        cmd!(sh, "git clone --depth 1 https://github.com/riscv-software-src/riscv-tests {riscv_tests_dir}")
            .run()
            .context("Failed to clone riscv-tests")?;
    }

    sh.change_dir(&riscv_tests_dir);
    let build_indicator = riscv_tests_dir.join("build_indicator");

    // Initialize submodules (needed for env)
    if !riscv_tests_dir.join("env/.git").exists() {
        cmd!(sh, "git submodule update --init --recursive")
            .run()
            .context("Failed to initialize submodules")?;
    }

    let patch_file = cur_dir.join("testbench/patches/riscv-tests-no-priv.patch");
    let patch_stamp = riscv_tests_dir.join(".svarog_patch_applied");
    if patch_file.exists() && !patch_stamp.exists() {
        cmd!(
            sh,
            "git apply --ignore-space-change --ignore-whitespace {patch_file}"
        )
        .run()
        .context("Failed to patch riscv-tests with simplified env")?;
        cmd!(sh, "touch {patch_stamp}").run()?;
        cmd!(sh, "rm -f {build_indicator}").run()?;
    }

    // Run autoconf if configure doesn't exist
    if !riscv_tests_dir.join("configure").exists() {
        cmd!(sh, "autoconf")
            .run()
            .context("Failed to run autoconf")?;
    }

    // Configure if Makefile doesn't exist
    if !riscv_tests_dir.join("Makefile").exists() {
        let prefix = riscv_tests_dir.join("install");
        cmd!(sh, "./configure --prefix={prefix} --with-xlen=32")
            .run()
            .context("Failed to configure riscv-tests")?;
    }

    if !build_indicator.exists() {
        let riscv_prefix = "riscv32-unknown-elf-";

        // Build rv32ui tests
        sh.change_dir(riscv_tests_dir.join("isa"));

        // Build all rv32ui tests
        cmd!(sh, "make -j XLEN=32 RISCV_PREFIX={riscv_prefix} rv32ui")
            .run()
            .context("Failed to build rv32ui test suite")?;
        cmd!(sh, "touch {build_indicator}").run()?;
    }

    Ok(())
}
