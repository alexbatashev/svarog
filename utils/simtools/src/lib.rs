mod config;
mod verilator;

pub use config::Config;
pub use verilator::{GeneratedVerilator, generate_verilator};
