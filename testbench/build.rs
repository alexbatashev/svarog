use anyhow::{Context, Result};
use std::path::PathBuf;
use xshell::{Shell, cmd};

fn main() -> Result<()> {
    let sh = Shell::new()?;

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=../src/main/**");
    println!("cargo:rerun-if-changed=verilator_wrapper.h");
    println!("cargo:rerun-if-changed=verilator_wrapper.cpp");
    println!("cargo:rerun-if-changed=src/bridge.rs");

    // TODO is there a better way to get workspace dir?
    sh.change_dir("..");

    sh.set_var("MILL_NO_SERVER", "1");

    // Generate Verilog using Chisel
    cmd!(
        sh,
        "./mill -i svarog.runMain svarog.VerilogGenerator --target-dir=target/generated/ --config=configs/svg-micro.yaml"
    )
    .run()
    .context("Failed to generate Verilog")?;

    let cur_dir = sh.current_dir();
    let verilog_dir = cur_dir.join("target/generated");
    let verilator_out_dir = cur_dir.join("target/verilator");

    // Run Verilator to generate C++ model
    std::fs::create_dir_all(&verilator_out_dir)?;

    let verilator_stamp = verilator_out_dir.join("verilator_build.stamp");
    let verilog_file = verilog_dir.join("VerilatorTop.sv");

    // Check if we need to run Verilator (if stamp doesn't exist or verilog changed)
    let need_verilator = !verilator_stamp.exists()
        || verilog_file.metadata()?.modified()? > verilator_stamp.metadata()?.modified()?;

    if need_verilator {
        println!("cargo:warning=Running Verilator to generate C++ model...");
        cmd!(
            sh,
            "verilator
             -Wno-fatal
             -Wno-UNUSEDSIGNAL
             --cc
             --trace
             -O3
             --build
             -Mdir {verilator_out_dir}
             {verilog_file}"
        )
        .run()
        .context("Failed to run Verilator")?;

        // Create stamp file
        std::fs::write(&verilator_stamp, "")?;
    }

    // Build riscv-tests
    let riscv_tests_dir = cur_dir.join("target/riscv-tests");

    if !riscv_tests_dir.exists() {
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

    // Switch back to workspace root for C++ compilation
    sh.change_dir(&cur_dir);

    // Find Verilator include directory
    let verilator_root = String::from_utf8(
        std::process::Command::new("verilator")
            .arg("--getenv")
            .arg("VERILATOR_ROOT")
            .output()
            .context("Failed to get VERILATOR_ROOT")?
            .stdout,
    )?;
    let verilator_root = verilator_root.trim();
    let verilator_include = PathBuf::from(verilator_root).join("include");

    // Use cxx-build to compile our wrapper only
    // Verilator already built everything with --build flag
    let mut build = cxx_build::bridge("src/bridge.rs");

    // Add wrapper file (use absolute path since we changed directories)
    let wrapper_cpp = cur_dir.join("testbench/verilator_wrapper.cpp");
    build.file(&wrapper_cpp);

    build
        .include(&verilator_out_dir)
        .include(&verilator_include)
        .include(&cur_dir)
        .flag_if_supported("-std=gnu++17")
        .flag_if_supported("-DVL_THREADED")
        // Suppress warnings from generated code
        .flag_if_supported("-w")
        .compile("verilator_wrapper");

    // Link against the pre-built Verilator archives
    println!("cargo:rustc-link-lib=static=verilator_wrapper");
    println!(
        "cargo:rustc-link-search=native={}",
        verilator_out_dir.display()
    );
    println!("cargo:rustc-link-lib=static=VVerilatorTop");
    println!("cargo:rustc-link-lib=static=verilated");

    Ok(())
}
