use std::path::Path;

use quote::quote;

pub fn generate_verilator(config_path: &Path) {
    let tokens = quote! {
        pub struct MyModel {

        }

        impl SimulatorImpl for MyModel {
            fn xlen(&self) -> u8 {
                32
            }

            fn isa(&self) -> &'static str {
                "rv32i_zmmul_zicsr_zicntr"
            }
        }
    };
}
