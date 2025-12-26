mod core;
mod models;
mod register_file;
mod uart;

// Re-export public API
pub use core::Simulator;
pub use register_file::{RegisterFile, TestResult};

// Re-export generated ModelId from models module
pub use models::ModelId;
pub use models::AVAILABLE_MODELS;

impl Simulator {
    /// List all available models
    pub fn available_models() -> &'static [ModelId] {
        AVAILABLE_MODELS
    }
}
