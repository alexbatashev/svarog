use anyhow::Result;
use proc_macro2::Span;
use quote::{format_ident, quote};
use std::fs::{self, File};
use std::io::Write;
use std::path::PathBuf;
use std::process::Command;
use syn::LitStr;

fn main() -> Result<()> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let workspace_root = manifest_dir
        .parent()
        .and_then(|p| p.parent())
        .ok_or_else(|| anyhow::anyhow!("Could not determine workspace root"))?
        .to_path_buf();

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=../../configs/");
    println!("cargo:rerun-if-changed=../../src/main/");

    let pattern = workspace_root.join("configs/*.yaml");
    let mut verilator = vec![];
    let mut verilator_monitored = vec![];
    let mut model_names = Vec::new();
    let mut verilator_constructors = Vec::new();
    let mut verilator_monitored_constructors = Vec::new();
    let mut include_paths = Vec::new();
    for entry in glob::glob(pattern.to_str().unwrap())? {
        let path = entry?;

        let model_info = simtools::generate_verilator(&path)?;
        let monitored_info = simtools::generate_verilator_with_monitors(&path)?;
        let simtools::GeneratedVerilator {
            model_name,
            model_identifier,
            wrapper_name,
            rust,
            verilator_output,
        } = model_info;

        println!(
            "cargo:rustc-link-search=native={}",
            verilator_output.display()
        );
        println!("cargo:rustc-link-lib=static={}", model_identifier);
        println!("cargo:rustc-link-lib=static=verilated");
        include_paths.push(
            verilator_output
                .parent()
                .ok_or_else(|| anyhow::anyhow!("Missing verilator output parent"))?
                .to_path_buf(),
        );
        include_paths.push(verilator_output.clone());

        verilator.push(rust);
        let model_name_lit = LitStr::new(&model_name, Span::call_site());
        model_names.push(model_name_lit.clone());
        let wrapper_ident = format_ident!("{}", wrapper_name);
        verilator_constructors.push(quote! {
            #model_name_lit => Some(std::rc::Rc::new(std::cell::RefCell::new(
                verilator::#wrapper_ident::new(),
            ))),
        });

        let simtools::GeneratedVerilator {
            model_identifier: monitored_identifier,
            wrapper_name: monitored_wrapper_name,
            rust: monitored_rust,
            verilator_output: monitored_output,
            ..
        } = monitored_info;
        println!(
            "cargo:rustc-link-search=native={}",
            monitored_output.display()
        );
        println!("cargo:rustc-link-lib=static={}", monitored_identifier);
        println!("cargo:rustc-link-lib=static=verilated");
        include_paths.push(
            monitored_output
                .parent()
                .ok_or_else(|| anyhow::anyhow!("Missing monitored output parent"))?
                .to_path_buf(),
        );
        include_paths.push(monitored_output.clone());

        verilator_monitored.push(monitored_rust);
        let monitored_wrapper_ident = format_ident!("{}", monitored_wrapper_name);
        verilator_monitored_constructors.push(quote! {
            #model_name_lit => Some(std::rc::Rc::new(std::cell::RefCell::new(
                verilator_monitored::#monitored_wrapper_ident::new(),
            ))),
        });
    }

    include_paths.sort();
    include_paths.dedup();

    let tokens = quote! {
        pub mod verilator {
            #(#verilator)*
        }

        pub mod verilator_monitored {
            #(#verilator_monitored)*
        }

        pub const VERILATOR_MODELS: &[&str] = &[#(#model_names),*];

        pub fn create_verilator(
            model_name: &str,
        ) -> Option<std::rc::Rc<std::cell::RefCell<dyn crate::core::SimulatorImpl>>> {
            match model_name {
                #(#verilator_constructors)*
                _ => None,
            }
        }

        pub fn create_verilator_monitored(
            model_name: &str,
        ) -> Option<std::rc::Rc<std::cell::RefCell<dyn crate::core::SimulatorImpl>>> {
            match model_name {
                #(#verilator_monitored_constructors)*
                _ => None,
            }
        }
    };

    let out_dir = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let generated_path = out_dir.join("generated.rs");
    let mut generated = File::create(&generated_path)?;
    let syntax_tree = syn::parse2(tokens).unwrap();
    let formatted = prettyplease::unparse(&syntax_tree);
    generated.write_all(formatted.as_bytes())?;

    let sc_time_path = out_dir.join("sc_time_stamp.cc");
    fs::write(&sc_time_path, "double sc_time_stamp() { return 0; }\n")?;

    let mut build = cxx_build::bridge(&generated_path);
    if let Some(include_path) = verilator_include_path() {
        build.include(include_path);
    }
    build.flag_if_supported("-w");
    for include_path in include_paths {
        build.include(include_path);
    }
    build.file(&sc_time_path);
    build.std("c++14").compile("simulator-cxx");

    Ok(())
}

fn verilator_include_path() -> Option<PathBuf> {
    if let Ok(root) = std::env::var("VERILATOR_ROOT") {
        return Some(PathBuf::from(root).join("include"));
    }

    let output = Command::new("verilator").arg("-V").output().ok()?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    for line in stdout.lines() {
        let line = line.trim();
        if line.starts_with("VERILATOR_ROOT") {
            if let Some((_, root)) = line.split_once('=') {
                let root = root.trim();
                if !root.is_empty() {
                    return Some(PathBuf::from(root).join("include"));
                }
            }
        }
    }

    None
}
