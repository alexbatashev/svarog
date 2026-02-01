use std::{fs::File, path::{Path, PathBuf}};

use quote::{format_ident, quote};
use xshell::{cmd, Shell};

use crate::config::Config;

pub fn generate_verilator(config_path: &Path) {
    let verilated = build_verilator(config_path).unwrap();

    let file = File::open(config_path).unwrap();
    let config: Config = yaml_serde::from_reader(file).unwrap();

    let model_name = config_path.file_stem().unwrap().to_str().unwrap();

    let safe_model_name = format_ident!("{}", model_name.replace("-", "_"));
    let class_name = format_ident!("{}_SvarogSoC", safe_model_name);

    let tokens = quote! {
        #[cxx::bridge]
        mod #safe_model_name {
            unsafe extern "C++" {
                include!();
            }
        }

        pub struct MyModel {

        }

        impl SimulatorImpl for MyModel {
            fn xlen(&self) -> u8 {
                32
            }

            fn isa(&self) -> &'static str {
                #model_name
            }
        }
    };
}

fn build_verilator(config_path: &Path) -> anyhow::Result<PathBuf> {
    let out_path = PathBuf::from(std::env::var("OUT_DIR")?).join("verilator");
    let model_name = config_path.file_stem().unwrap().to_str().unwrap().replace("-", "_");

    let sh = Shell::new().unwrap();

    cmd!(sh, "./mill -i svarog.runMain svarog.VerilogGenerator --simulator-debug-iface=true --target-dir={out_path} --config={config_path}").run()?;

    let verilog_file = out_path.join("SvarogSoC.sv");
    let verilator_output = out_path.join("verilated");

    cmd!(sh, "verilator
        --prefix {model_name}
         -Wno-fatal
         -Wno-UNUSEDSIGNAL
         --cc
         --trace
         -O3
         --build
         --threads 4
         --no-assert
         -Mdir {verilator_output}
         {verilog_file}"
    ).run()?;

    Ok(verilator_output)
}
