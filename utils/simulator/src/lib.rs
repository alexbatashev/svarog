mod arc;
mod uart;

use std::path::Path;

use thiserror::Error;

#[derive(Debug, Clone, Error)]
pub enum Error {
    #[error("Unknown simulation error")]
    Unknown,
}

pub trait Simulator {
    fn step(&self) -> Result<(), Error>;
    fn print_stats(&self) {
        unimplemented!("Stats not supported")
    }
    fn load_binary(&self, _path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        unimplemented!("ELF binary loading not supported yet")
    }
}

pub fn list_arcilator_models() -> Vec<Box<dyn Simulator>> {
    vec![]
}
