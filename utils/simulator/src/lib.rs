pub mod arc;
mod uart;

use std::path::Path;

use thiserror::Error;

#[derive(Debug, Clone, Error)]
pub enum Error {
    #[error("Unknown simulation error")]
    Unknown,
}

pub trait Simulator {
    fn name(&self) -> &'static str;
    fn io(&self) -> &'static [crate::arc::Signal];
    fn hierarchy(&self) -> &'static crate::arc::StaticHierarchy;
    fn state_buf(&self) -> &[u8];
    fn state_buf_mut(&mut self) -> &mut [u8];
    fn step(&mut self) -> Result<(), Error>;
    fn print_stats(&self) {
        unimplemented!("Stats not supported")
    }
    fn load_binary(
        &mut self,
        path: &Path,
        watchpoint_symbol: Option<&str>,
    ) -> anyhow::Result<Option<u32>> {
        let _ = (path, watchpoint_symbol);
        unimplemented!("ELF binary loading not supported yet")
    }
}

pub use uart::UartDecoder;

include!(concat!(env!("OUT_DIR"), "/arcilator.rs"));

pub fn list_arcilator_models() -> Vec<Box<dyn Simulator>> {
    arcilator_gen::list_arcilator_models()
}
