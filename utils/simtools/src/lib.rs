mod config;
mod utils;
mod verilator;

pub use config::Config;
pub use verilator::{generate_verilator, generate_verilator_with_monitors, GeneratedVerilator};

pub use utils::clone_repo;
