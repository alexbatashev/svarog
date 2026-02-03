use anyhow::{Context, Result};
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use xshell::{Shell, cmd};
use quote::quote;
use std::io::Write;

// fn discover_models(workspace_root: &Path) -> Result<Vec<PathBuf>> {
//     let pattern = workspace_root.join("configs/*.yaml");
//     let mut models = Vec::new();

//     for entry in glob::glob(pattern.to_str().unwrap())? {
//         let path = entry?;
//         models.push(ModelInfo::from_yaml_path(path)?);
//     }

//     if models.is_empty() {
//         anyhow::bail!("No config files found in configs/ directory");
//     }

//     // Sort for deterministic builds
//     models.sort_by(|a, b| a.name.cmp(&b.name));

//     Ok(models)
// }

// fn link_verilator_libs(workspace_root: &Path, model: &ModelInfo) -> Result<()> {
//     let verilator_dir = workspace_root.join(format!("target/verilator/{}", model.name));

//     println!("cargo:rustc-link-search=native={}", verilator_dir.display());

//     // Each config's Verilator build produces its own VSvarogSoC library
//     // Because we used --prefix, they have different symbol names
//     println!(
//         "cargo:rustc-link-lib=static=VSvarogSoC_{}",
//         model.identifier
//     );
//     println!("cargo:rustc-link-lib=static=verilated");

//     Ok(())
// }

fn main() -> Result<()> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let workspace_root = manifest_dir
        .parent()
        .and_then(|p| p.parent())
        .ok_or_else(|| anyhow::anyhow!("Could not determine workspace root"))?
        .to_path_buf();

    let sh = Shell::new()?;

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=../../configs/");
    println!("cargo:rerun-if-changed=../../src/main/");

    let pattern = workspace_root.join("configs/*.yaml");
    let mut verilator = vec![];
    for entry in glob::glob(pattern.to_str().unwrap())? {
        let path = entry?;

        let model_info = simtools::generate_verilator(&path)?;

        verilator.push(model_info.rust);
    }

    let tokens = quote!{
        pub mod verilator {
            #(#verilator)*
        }
    };

    let mut generated = File::create(PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("generated.rs"))?;
    let syntax_tree = syn::parse2(tokens).unwrap();
    let formatted = prettyplease::unparse(&syntax_tree);
    generated.write_all(formatted.as_bytes())?;

    Ok(())
}
