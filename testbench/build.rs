use anyhow::{Context, Result};
use std::path::Path;
use xshell::{Shell, cmd};

fn main() -> Result<()> {
    let sh = Shell::new()?;

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=direct-tests");

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

    // Build direct tests
    sh.change_dir(&cur_dir);
    build_direct_tests(&sh, &cur_dir)?;

    Ok(())
}

/// Build handwritten assembly tests from testbench/direct-tests/
fn build_direct_tests(sh: &Shell, workspace_dir: &Path) -> Result<()> {
    let direct_tests_src = workspace_dir.join("testbench/direct-tests/rv32");
    let direct_tests_out = workspace_dir.join("target/direct-tests/rv32");
    let common_dir = direct_tests_src.join("common");

    // Create output directory
    std::fs::create_dir_all(&direct_tests_out)?;

    let crt0 = common_dir.join("crt0.S");
    let linker_script = common_dir.join("linker.ld");

    // Check that common files exist
    if !crt0.exists() || !linker_script.exists() {
        anyhow::bail!(
            "Missing common files: crt0.S or linker.ld in {:?}",
            common_dir
        );
    }

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

        // Check if rebuild is needed
        let needs_rebuild = if output_elf.exists() {
            let elf_modified = std::fs::metadata(&output_elf)?.modified()?;
            let src_modified = std::fs::metadata(&path)?.modified()?;
            let crt0_modified = std::fs::metadata(&crt0)?.modified()?;
            let linker_modified = std::fs::metadata(&linker_script)?.modified()?;

            src_modified > elf_modified
                || crt0_modified > elf_modified
                || linker_modified > elf_modified
        } else {
            true
        };

        if needs_rebuild {
            println!("cargo:warning=Building direct test: {}", test_name);

            // Compile and link in one step
            cmd!(
                sh,
                "riscv32-unknown-elf-gcc -march=rv32i_zicsr -mabi=ilp32 -nostdlib -nostartfiles -static -T {linker_script} -o {output_elf} {crt0} {path}"
            )
            .run()
            .with_context(|| format!("Failed to build direct test: {}", test_name))?;
        }
    }

    Ok(())
}
