/// Register file state
#[derive(Debug, Clone)]
pub struct RegisterFile {
    regs: [u32; 32],
}

impl RegisterFile {
    pub fn new() -> Self {
        Self { regs: [0; 32] }
    }

    pub fn get(&self, idx: u8) -> u32 {
        if idx < 32 { self.regs[idx as usize] } else { 0 }
    }

    pub fn set(&mut self, idx: u8, value: u32) {
        if idx < 32 && idx != 0 {
            // x0 is always 0
            self.regs[idx as usize] = value;
        }
    }
}

impl Default for RegisterFile {
    fn default() -> Self {
        Self::new()
    }
}

/// Test result containing register state
#[derive(Debug)]
pub struct TestResult {
    pub regs: RegisterFile,
    pub exit_code: Option<u32>,
}
