use anyhow::Result;
use cxx::UniquePtr;
use std::cell::RefCell;

// Include auto-generated config registry which declares the module structure
// The generated.rs file is created by build.rs in the src directory
include!("generated.rs");

/// Internal enum wrapping different Verilator models
pub(crate) enum VerilatorModelVariant {
    SvgMicro(RefCell<UniquePtr<ffi::svg_micro::ffi::VerilatorModel>>),
    // Future variants will be added here by extending this enum
}

#[allow(dead_code)]
#[allow(non_snake_case)]
impl VerilatorModelVariant {
    // Simulation control
    pub fn eval(&self) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().eval(),
        }
    }

    pub fn final_eval(&self) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().final_eval(),
        }
    }

    // VCD tracing
    pub fn open_vcd(&self, path: &str) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().open_vcd(path),
        }
    }

    pub fn dump_vcd(&self, timestamp: u64) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().dump_vcd(timestamp),
        }
    }

    pub fn close_vcd(&self) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().close_vcd(),
        }
    }

    // Clock and reset
    pub fn get_clock(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_clock(),
        }
    }

    pub fn set_clock(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().set_clock(value),
        }
    }

    pub fn get_reset(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_reset(),
        }
    }

    pub fn set_reset(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().set_reset(value),
        }
    }

    // Debug hart interface - ID routing
    pub fn get_debug_hart_in_id_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_id_valid(),
        }
    }

    pub fn set_debug_hart_in_id_valid(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_id_valid(value),
        }
    }

    pub fn get_debug_hart_in_id_bits(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_id_bits(),
        }
    }

    pub fn set_debug_hart_in_id_bits(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_id_bits(value),
        }
    }

    // Debug hart interface - Halt control
    pub fn get_debug_hart_in_bits_halt_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_halt_valid(),
        }
    }

    pub fn set_debug_hart_in_bits_halt_valid(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_halt_valid(value),
        }
    }

    pub fn get_debug_hart_in_bits_halt_bits(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_halt_bits(),
        }
    }

    pub fn set_debug_hart_in_bits_halt_bits(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_halt_bits(value),
        }
    }

    // Debug hart interface - Breakpoint
    pub fn get_debug_hart_in_bits_breakpoint_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_breakpoint_valid(),
        }
    }

    pub fn set_debug_hart_in_bits_breakpoint_valid(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_breakpoint_valid(value),
        }
    }

    pub fn get_debug_hart_in_bits_breakpoint_bits_pc(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_breakpoint_bits_pc(),
        }
    }

    pub fn set_debug_hart_in_bits_breakpoint_bits_pc(&self, value: u32) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_breakpoint_bits_pc(value),
        }
    }

    // Debug hart interface - Watchpoint
    pub fn get_debug_hart_in_bits_watchpoint_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_watchpoint_valid(),
        }
    }

    pub fn set_debug_hart_in_bits_watchpoint_valid(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_watchpoint_valid(value),
        }
    }

    pub fn get_debug_hart_in_bits_watchpoint_bits_addr(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_watchpoint_bits_addr(),
        }
    }

    pub fn set_debug_hart_in_bits_watchpoint_bits_addr(&self, value: u32) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_watchpoint_bits_addr(value),
        }
    }

    // Debug hart interface - Set PC
    pub fn get_debug_hart_in_bits_setPC_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_setPC_valid(),
        }
    }

    pub fn set_debug_hart_in_bits_setPC_valid(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_setPC_valid(value),
        }
    }

    pub fn get_debug_hart_in_bits_setPC_bits_pc(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_setPC_bits_pc(),
        }
    }

    pub fn set_debug_hart_in_bits_setPC_bits_pc(&self, value: u32) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_setPC_bits_pc(value),
        }
    }

    // Debug hart interface - Register access
    pub fn get_debug_hart_in_bits_register_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_register_valid(),
        }
    }

    pub fn set_debug_hart_in_bits_register_valid(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_register_valid(value),
        }
    }

    pub fn get_debug_hart_in_bits_register_bits_reg(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_register_bits_reg(),
        }
    }

    pub fn set_debug_hart_in_bits_register_bits_reg(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_register_bits_reg(value),
        }
    }

    pub fn get_debug_hart_in_bits_register_bits_write(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_register_bits_write(),
        }
    }

    pub fn set_debug_hart_in_bits_register_bits_write(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_register_bits_write(value),
        }
    }

    pub fn get_debug_hart_in_bits_register_bits_data(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_hart_in_bits_register_bits_data(),
        }
    }

    pub fn set_debug_hart_in_bits_register_bits_data(&self, value: u32) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_hart_in_bits_register_bits_data(value),
        }
    }

    // Debug memory interface - Request
    pub fn get_debug_mem_in_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_in_valid(),
        }
    }

    pub fn set_debug_mem_in_valid(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().set_debug_mem_in_valid(value),
        }
    }

    pub fn get_debug_mem_in_ready(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_in_ready(),
        }
    }

    pub fn get_debug_mem_in_bits_addr(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_in_bits_addr(),
        }
    }

    pub fn set_debug_mem_in_bits_addr(&self, value: u32) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_mem_in_bits_addr(value),
        }
    }

    pub fn get_debug_mem_in_bits_write(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_in_bits_write(),
        }
    }

    pub fn set_debug_mem_in_bits_write(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_mem_in_bits_write(value),
        }
    }

    pub fn get_debug_mem_in_bits_data(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_in_bits_data(),
        }
    }

    pub fn set_debug_mem_in_bits_data(&self, value: u32) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_mem_in_bits_data(value),
        }
    }

    pub fn get_debug_mem_in_bits_reqWidth(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_in_bits_reqWidth(),
        }
    }

    pub fn set_debug_mem_in_bits_reqWidth(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_mem_in_bits_reqWidth(value),
        }
    }

    pub fn get_debug_mem_in_bits_instr(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_in_bits_instr(),
        }
    }

    pub fn set_debug_mem_in_bits_instr(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model
                .borrow_mut()
                .pin_mut()
                .set_debug_mem_in_bits_instr(value),
        }
    }

    // Debug memory interface - Response
    pub fn get_debug_mem_res_ready(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_res_ready(),
        }
    }

    pub fn set_debug_mem_res_ready(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().set_debug_mem_res_ready(value),
        }
    }

    pub fn get_debug_mem_res_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_res_valid(),
        }
    }

    pub fn get_debug_mem_res_bits(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_mem_res_bits(),
        }
    }

    // Debug register interface - Response
    pub fn get_debug_reg_res_ready(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_reg_res_ready(),
        }
    }

    pub fn set_debug_reg_res_ready(&self, value: u8) {
        match self {
            Self::SvgMicro(model) => model.borrow_mut().pin_mut().set_debug_reg_res_ready(value),
        }
    }

    pub fn get_debug_reg_res_valid(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_reg_res_valid(),
        }
    }

    pub fn get_debug_reg_res_bits(&self) -> u32 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_reg_res_bits(),
        }
    }

    // Debug status
    pub fn get_debug_halted(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_debug_halted(),
        }
    }

    // UART signals
    pub fn get_uart_0_txd(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_uart_0_txd(),
        }
    }

    pub fn get_uart_1_txd(&self) -> u8 {
        match self {
            Self::SvgMicro(model) => model.borrow().get_uart_1_txd(),
        }
    }
}

/// Create a simulator for the specified model
pub(crate) fn create_model(model_id: ModelId) -> Result<VerilatorModelVariant> {
    match model_id {
        ModelId::SvgMicro => Ok(VerilatorModelVariant::SvgMicro(RefCell::new(
            ffi::svg_micro::ffi::create_verilator_model(),
        ))),
    }
}
