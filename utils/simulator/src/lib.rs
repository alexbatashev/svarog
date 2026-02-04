mod core;
mod models;
mod register_file;
mod uart;

// Re-export public API
pub use core::{Backend, Simulator};
pub use register_file::{RegisterFile, TestResult};

impl Simulator {
    /// List all available models
    pub fn available_models(backend: Backend) -> &'static [&'static str] {
        match backend {
            Backend::Verilator | Backend::VerilatorMonitored => crate::models::VERILATOR_MODELS,
        }
    }
}
