#include "verilator_wrapper.h"
#include "VVerilatorTop.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

namespace svarog {

VerilatorModel::VerilatorModel() {
    // Initialize Verilator
    Verilated::commandArgs(0, nullptr);
    Verilated::traceEverOn(true);

    // Create the model
    model_ = std::make_unique<VVerilatorTop>();
}

VerilatorModel::~VerilatorModel() {
    close_vcd();
    if (model_) {
        model_->final();
    }
}

void VerilatorModel::open_vcd(rust::Str path) {
    if (vcd_) {
        vcd_->close();
    }
    vcd_ = std::make_unique<VerilatedVcdC>();
    model_->trace(vcd_.get(), 99);  // Trace 99 levels of hierarchy
    vcd_->open(std::string(path).c_str());
}

void VerilatorModel::dump_vcd(uint64_t timestamp) {
    if (vcd_) {
        vcd_->dump(timestamp);
    }
}

void VerilatorModel::close_vcd() {
    if (vcd_) {
        vcd_->close();
        vcd_.reset();
    }
}

void VerilatorModel::eval() {
    model_->eval();
}

void VerilatorModel::final_eval() {
    model_->final();
}

// Clock and reset
uint8_t VerilatorModel::get_clock() const { return model_->clock; }
void VerilatorModel::set_clock(uint8_t value) { model_->clock = value; }
uint8_t VerilatorModel::get_reset() const { return model_->reset; }
void VerilatorModel::set_reset(uint8_t value) { model_->reset = value; }

// Debug hart interface - ID routing
uint8_t VerilatorModel::get_debug_hart_in_id_valid() const {
    return model_->io_debug_hart_in_id_valid;
}
void VerilatorModel::set_debug_hart_in_id_valid(uint8_t value) {
    model_->io_debug_hart_in_id_valid = value;
}
uint8_t VerilatorModel::get_debug_hart_in_id_bits() const {
    return model_->io_debug_hart_in_id_bits;
}
void VerilatorModel::set_debug_hart_in_id_bits(uint8_t value) {
    model_->io_debug_hart_in_id_bits = value;
}

// Debug hart interface - Halt control
uint8_t VerilatorModel::get_debug_hart_in_bits_halt_valid() const {
    return model_->io_debug_hart_in_bits_halt_valid;
}
void VerilatorModel::set_debug_hart_in_bits_halt_valid(uint8_t value) {
    model_->io_debug_hart_in_bits_halt_valid = value;
}
uint8_t VerilatorModel::get_debug_hart_in_bits_halt_bits() const {
    return model_->io_debug_hart_in_bits_halt_bits;
}
void VerilatorModel::set_debug_hart_in_bits_halt_bits(uint8_t value) {
    model_->io_debug_hart_in_bits_halt_bits = value;
}

// Debug hart interface - Breakpoint
uint8_t VerilatorModel::get_debug_hart_in_bits_breakpoint_valid() const {
    return model_->io_debug_hart_in_bits_breakpoint_valid;
}
void VerilatorModel::set_debug_hart_in_bits_breakpoint_valid(uint8_t value) {
    model_->io_debug_hart_in_bits_breakpoint_valid = value;
}
uint32_t VerilatorModel::get_debug_hart_in_bits_breakpoint_bits_pc() const {
    return model_->io_debug_hart_in_bits_breakpoint_bits_pc;
}
void VerilatorModel::set_debug_hart_in_bits_breakpoint_bits_pc(uint32_t value) {
    model_->io_debug_hart_in_bits_breakpoint_bits_pc = value;
}

// Debug hart interface - Watchpoint
uint8_t VerilatorModel::get_debug_hart_in_bits_watchpoint_valid() const {
    return model_->io_debug_hart_in_bits_watchpoint_valid;
}
void VerilatorModel::set_debug_hart_in_bits_watchpoint_valid(uint8_t value) {
    model_->io_debug_hart_in_bits_watchpoint_valid = value;
}
uint32_t VerilatorModel::get_debug_hart_in_bits_watchpoint_bits_addr() const {
    return model_->io_debug_hart_in_bits_watchpoint_bits_addr;
}
void VerilatorModel::set_debug_hart_in_bits_watchpoint_bits_addr(uint32_t value) {
    model_->io_debug_hart_in_bits_watchpoint_bits_addr = value;
}

// Debug hart interface - Set PC
uint8_t VerilatorModel::get_debug_hart_in_bits_setPC_valid() const {
    return model_->io_debug_hart_in_bits_setPC_valid;
}
void VerilatorModel::set_debug_hart_in_bits_setPC_valid(uint8_t value) {
    model_->io_debug_hart_in_bits_setPC_valid = value;
}
uint32_t VerilatorModel::get_debug_hart_in_bits_setPC_bits_pc() const {
    return model_->io_debug_hart_in_bits_setPC_bits_pc;
}
void VerilatorModel::set_debug_hart_in_bits_setPC_bits_pc(uint32_t value) {
    model_->io_debug_hart_in_bits_setPC_bits_pc = value;
}

// Debug hart interface - Register access
uint8_t VerilatorModel::get_debug_hart_in_bits_register_valid() const {
    return model_->io_debug_hart_in_bits_register_valid;
}
void VerilatorModel::set_debug_hart_in_bits_register_valid(uint8_t value) {
    model_->io_debug_hart_in_bits_register_valid = value;
}
uint8_t VerilatorModel::get_debug_hart_in_bits_register_bits_reg() const {
    return model_->io_debug_hart_in_bits_register_bits_reg;
}
void VerilatorModel::set_debug_hart_in_bits_register_bits_reg(uint8_t value) {
    model_->io_debug_hart_in_bits_register_bits_reg = value;
}
uint8_t VerilatorModel::get_debug_hart_in_bits_register_bits_write() const {
    return model_->io_debug_hart_in_bits_register_bits_write;
}
void VerilatorModel::set_debug_hart_in_bits_register_bits_write(uint8_t value) {
    model_->io_debug_hart_in_bits_register_bits_write = value;
}
uint32_t VerilatorModel::get_debug_hart_in_bits_register_bits_data() const {
    return model_->io_debug_hart_in_bits_register_bits_data;
}
void VerilatorModel::set_debug_hart_in_bits_register_bits_data(uint32_t value) {
    model_->io_debug_hart_in_bits_register_bits_data = value;
}

// Debug memory interface - Request
uint8_t VerilatorModel::get_debug_mem_in_valid() const {
    return model_->io_debug_mem_in_valid;
}
void VerilatorModel::set_debug_mem_in_valid(uint8_t value) {
    model_->io_debug_mem_in_valid = value;
}
uint8_t VerilatorModel::get_debug_mem_in_ready() const {
    return model_->io_debug_mem_in_ready;
}
uint32_t VerilatorModel::get_debug_mem_in_bits_addr() const {
    return model_->io_debug_mem_in_bits_addr;
}
void VerilatorModel::set_debug_mem_in_bits_addr(uint32_t value) {
    model_->io_debug_mem_in_bits_addr = value;
}
uint8_t VerilatorModel::get_debug_mem_in_bits_write() const {
    return model_->io_debug_mem_in_bits_write;
}
void VerilatorModel::set_debug_mem_in_bits_write(uint8_t value) {
    model_->io_debug_mem_in_bits_write = value;
}
uint32_t VerilatorModel::get_debug_mem_in_bits_data() const {
    return model_->io_debug_mem_in_bits_data;
}
void VerilatorModel::set_debug_mem_in_bits_data(uint32_t value) {
    model_->io_debug_mem_in_bits_data = value;
}
uint8_t VerilatorModel::get_debug_mem_in_bits_reqWidth() const {
    return model_->io_debug_mem_in_bits_reqWidth;
}
void VerilatorModel::set_debug_mem_in_bits_reqWidth(uint8_t value) {
    model_->io_debug_mem_in_bits_reqWidth = value;
}
uint8_t VerilatorModel::get_debug_mem_in_bits_instr() const {
    return model_->io_debug_mem_in_bits_instr;
}
void VerilatorModel::set_debug_mem_in_bits_instr(uint8_t value) {
    model_->io_debug_mem_in_bits_instr = value;
}

// Debug memory interface - Response
uint8_t VerilatorModel::get_debug_mem_res_ready() const {
    return model_->io_debug_mem_res_ready;
}
void VerilatorModel::set_debug_mem_res_ready(uint8_t value) {
    model_->io_debug_mem_res_ready = value;
}
uint8_t VerilatorModel::get_debug_mem_res_valid() const {
    return model_->io_debug_mem_res_valid;
}
uint32_t VerilatorModel::get_debug_mem_res_bits() const {
    return model_->io_debug_mem_res_bits;
}

// Debug register interface - Response
uint8_t VerilatorModel::get_debug_reg_res_ready() const {
    return model_->io_debug_reg_res_ready;
}
void VerilatorModel::set_debug_reg_res_ready(uint8_t value) {
    model_->io_debug_reg_res_ready = value;
}
uint8_t VerilatorModel::get_debug_reg_res_valid() const {
    return model_->io_debug_reg_res_valid;
}
uint32_t VerilatorModel::get_debug_reg_res_bits() const {
    return model_->io_debug_reg_res_bits;
}

// Debug status
uint8_t VerilatorModel::get_debug_halted() const {
    return model_->io_debug_halted;
}

std::unique_ptr<VerilatorModel> create_verilator_model() {
    return std::make_unique<VerilatorModel>();
}

} // namespace svarog
