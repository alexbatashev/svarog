use anyhow::{Context, Result};
use std::path::Path;
use xshell::{Shell, cmd};

fn main() -> Result<()> {
    let sh = Shell::new()?;

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=../src/main/**");

    // Save testbench directory before changing
    let testbench_dir = sh.current_dir();

    // Step 1: Generate Verilog
    println!("cargo:warning=Generating Verilog...");
    sh.change_dir("..");
    cmd!(sh, "mill svarog.runMain svarog.GenerateVerilatorTop")
        .run()
        .context("Failed to generate Verilog")?;

    // Step 2: Setup and build riscv-tests
    println!("cargo:warning=Setting up riscv-tests...");
    let riscv_tests_dir = testbench_dir.join("riscv-tests");
    let tests_output_dir = testbench_dir.join("tests");

    // Clone riscv-tests if not present
    if !riscv_tests_dir.exists() {
        println!("cargo:warning=Cloning riscv-tests repository...");
        cmd!(
            sh,
            "git clone --depth=1 https://github.com/riscv-software-src/riscv-tests {riscv_tests_dir}"
        )
        .run()
        .context("Failed to clone riscv-tests")?;
    }

    sh.change_dir(&riscv_tests_dir);

    // Initialize submodules (needed for env)
    if !riscv_tests_dir.join("env/.git").exists() {
        println!("cargo:warning=Initializing submodules...");
        cmd!(sh, "git submodule update --init --recursive")
            .run()
            .context("Failed to initialize submodules")?;
    }

    // Run autoconf if configure doesn't exist
    if !riscv_tests_dir.join("configure").exists() {
        println!("cargo:warning=Running autoconf...");
        cmd!(sh, "autoconf")
            .run()
            .context("Failed to run autoconf")?;
    }

    // Configure if Makefile doesn't exist
    if !riscv_tests_dir.join("Makefile").exists() {
        println!("cargo:warning=Configuring riscv-tests...");
        let prefix = riscv_tests_dir.join("install");
        cmd!(sh, "./configure --prefix={prefix} --with-xlen=32")
            .run()
            .context("Failed to configure riscv-tests")?;
    }

    // Detect RISC-V toolchain prefix
    let riscv_prefix = if cmd!(sh, "which riscv32-unknown-elf-gcc").read().is_ok() {
        "riscv32-unknown-elf-"
    } else if cmd!(sh, "which riscv64-unknown-elf-gcc").read().is_ok() {
        println!("cargo:warning=Using riscv64-unknown-elf- toolchain for rv32");
        "riscv64-unknown-elf-"
    } else {
        return Err(anyhow::anyhow!(
            "No RISC-V toolchain found. Install riscv32-unknown-elf-gcc or riscv64-unknown-elf-gcc"
        ));
    };

    // Build rv32ui tests
    println!("cargo:warning=Building rv32ui tests...");
    sh.change_dir(riscv_tests_dir.join("isa"));

    // Check toolchain by building a simple test
    cmd!(sh, "make XLEN=32 RISCV_PREFIX={riscv_prefix} rv32ui-p-add")
        .run()
        .context("Failed to build rv32ui tests")?;

    // Build all rv32ui tests
    cmd!(sh, "make -j XLEN=32 RISCV_PREFIX={riscv_prefix} rv32ui")
        .run()
        .context("Failed to build rv32ui test suite")?;

    sh.change_dir(&testbench_dir);

    // Create output directory for hex files
    std::fs::create_dir_all(&tests_output_dir)
        .context("Failed to create tests output directory")?;

    // Convert rv32ui ELF files to hex
    println!("cargo:warning=Converting ELF files to hex...");
    let isa_dir = riscv_tests_dir.join("isa");

    let test_files = std::fs::read_dir(&isa_dir)
        .context("Failed to read isa directory")?
        .filter_map(|entry| entry.ok())
        .filter(|entry| {
            let name = entry.file_name();
            let name_str = name.to_string_lossy();
            name_str.starts_with("rv32ui-p-") && !name_str.contains(".dump")
        })
        .collect::<Vec<_>>();

    println!("cargo:warning=Found {} rv32ui tests", test_files.len());
    let test_count = test_files.len();

    for entry in test_files {
        let elf_path = entry.path();
        let test_name = entry.file_name();
        let test_name_str = test_name.to_string_lossy();

        // Create hex file path
        let hex_path = tests_output_dir.join(format!("{}.hex", test_name_str));
        let bin_path = tests_output_dir.join(format!("{}.bin", test_name_str));

        // Convert ELF to binary
        let objcopy = format!("{}objcopy", riscv_prefix);
        cmd!(sh, "{objcopy} -O binary {elf_path} {bin_path}")
            .run()
            .with_context(|| format!("Failed to convert {} to binary", test_name_str))?;

        // Convert binary to hex (word-aligned, 32-bit little-endian)
        generate_hex_file(&bin_path, &hex_path)
            .with_context(|| format!("Failed to generate hex for {}", test_name_str))?;

        // Also create a verilog hex format for easy loading
        let verilog_hex_path = tests_output_dir.join(format!("{}.vh", test_name_str));
        cmd!(sh, "{objcopy} -O verilog {elf_path} {verilog_hex_path}")
            .run()
            .with_context(|| format!("Failed to create verilog hex for {}", test_name_str))?;

        println!("cargo:warning=Converted {}", test_name_str);
    }

    // Generate test manifest
    generate_test_manifest(&tests_output_dir)?;

    println!(
        "cargo:warning=Successfully prepared {} rv32ui tests",
        test_count
    );

    Ok(())
}

/// Convert a binary file to a hex file with 32-bit words
fn generate_hex_file(bin_path: &Path, hex_path: &Path) -> Result<()> {
    let data = std::fs::read(bin_path).context("Failed to read binary file")?;

    let mut hex_words = Vec::new();

    // Process in 4-byte chunks (32-bit words)
    for chunk in data.chunks(4) {
        let mut word = 0u32;
        for (i, &byte) in chunk.iter().enumerate() {
            word |= (byte as u32) << (i * 8);
        }
        hex_words.push(format!("{:08x}", word));
    }

    let hex_content = hex_words.join("\n");
    std::fs::write(hex_path, hex_content).context("Failed to write hex file")?;

    Ok(())
}

/// Generate a Rust file listing all available tests
fn generate_test_manifest(tests_dir: &Path) -> Result<()> {
    let mut test_names = Vec::new();

    for entry in std::fs::read_dir(tests_dir)? {
        let entry = entry?;
        let name = entry.file_name();
        let name_str = name.to_string_lossy();

        if name_str.ends_with(".hex") && name_str.starts_with("rv32ui-p-") {
            let test_name = name_str.trim_end_matches(".hex").to_string();
            test_names.push(test_name);
        }
    }

    test_names.sort();

    let manifest_path = tests_dir.join("manifest.rs");
    let mut manifest_content = String::from(
        "// Auto-generated test manifest\n\
         // Do not edit manually\n\n\
         pub const RV32UI_TESTS: &[&str] = &[\n",
    );

    for test_name in &test_names {
        manifest_content.push_str(&format!("    \"{}\",\n", test_name));
    }

    manifest_content.push_str("];\n");

    std::fs::write(&manifest_path, manifest_content).context("Failed to write test manifest")?;

    println!(
        "cargo:warning=Generated test manifest with {} tests",
        test_names.len()
    );

    Ok(())
}
