mod core;
mod register_file;
mod uart;
mod models;

// Re-export public API
pub use core::Simulator;
pub use register_file::{RegisterFile, TestResult};

impl Simulator {
    /// List all available models
    pub fn available_models() -> &'static [()] {
        todo!()
    }
}
