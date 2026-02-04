use std::{
    fs::File,
    io::Write,
    path::{Path, PathBuf},
};

use quote::{format_ident, quote, TokenStreamExt};
use xshell::{cmd, Shell};

use crate::config::Config;

pub struct GeneratedVerilator {
    pub model_name: String,
    pub model_identifier: String,
    pub wrapper_name: String,
    pub rust: proc_macro2::TokenStream,
    pub verilator_output: PathBuf,
}

pub fn generate_verilator(config_path: &Path) -> anyhow::Result<GeneratedVerilator> {
    generate_verilator_with_options(
        config_path,
        VerilatorOptions {
            with_monitors: false,
        },
    )
}

pub fn generate_verilator_with_monitors(config_path: &Path) -> anyhow::Result<GeneratedVerilator> {
    generate_verilator_with_options(
        config_path,
        VerilatorOptions {
            with_monitors: true,
        },
    )
}

struct VerilatorOptions {
    with_monitors: bool,
}

fn generate_verilator_with_options(
    config_path: &Path,
    options: VerilatorOptions,
) -> anyhow::Result<GeneratedVerilator> {
    let model_name = config_path
        .file_stem()
        .and_then(|stem| stem.to_str())
        .ok_or_else(|| anyhow::anyhow!("Invalid config filename: {config_path:?}"))?;
    let wrapper_suffix = if options.with_monitors {
        "-monitored"
    } else {
        ""
    };
    let wrapper_model_name = format!("{model_name}{wrapper_suffix}");
    let model_identifier = wrapper_model_name.replace("-", "_");
    let verilator_output = build_verilator(config_path, &model_identifier, options.with_monitors)?;

    let file = File::open(config_path)?;
    let config: Config = yaml_serde::from_reader(file)?;

    let wrapper_pascal = to_pascal_case(&wrapper_model_name);
    let struct_name = format_ident!("{}Wrapper", wrapper_pascal);
    let verilator_type = format_ident!("VerilatorModel{}", wrapper_pascal);
    let factory_fn = format_ident!("create_verilator_model_{}", model_identifier);
    let header_name = format!("verilator_{}_wrapper.h", model_identifier);
    let header_path = PathBuf::from(std::env::var("OUT_DIR")?)
        .join("verilator")
        .join(header_name);
    let include_path = header_path.to_str();
    let namespace = format!("svarog::{}", model_identifier);
    let ffi_ident = format_ident!("ffi_{}", model_identifier);
    let xlen = config.xlen();
    let isa = config.isa().unwrap_or("rv32i").to_string();
    let num_uarts = config.num_uarts();

    let mut uart_bridge = quote! {};
    for i in 0..num_uarts {
        let get_uart = format_ident!("get_uart_{}_txd", i);
        let set_uart = format_ident!("set_uart_{}_rxd", i);
        uart_bridge.append_all(quote! {
            fn #get_uart(&self) -> u8;
            fn #set_uart(self: Pin<&mut #verilator_type>, value: u8);
        });
    }

    let uart0_get = if num_uarts > 0 {
        quote! { self.model.borrow().get_uart_0_txd() }
    } else {
        quote! { 0 }
    };
    let uart0_set = if num_uarts > 0 {
        quote! { self.model.borrow_mut().pin_mut().set_uart_0_rxd(value); }
    } else {
        quote! { let _ = value; }
    };
    let uart1_get = if num_uarts > 1 {
        quote! { self.model.borrow().get_uart_1_txd() }
    } else {
        quote! { 0 }
    };
    let uart1_set = if num_uarts > 1 {
        quote! { self.model.borrow_mut().pin_mut().set_uart_1_rxd(value); }
    } else {
        quote! { let _ = value; }
    };

    let tokens = quote! {
        use std::cell::RefCell;

        use cxx::UniquePtr;

        use crate::core::SimulatorImpl;

        #[cxx::bridge(namespace = #namespace)]
        pub mod #ffi_ident {
            #[allow(dead_code)]
            unsafe extern "C++" {
                include!(#include_path);

                type #verilator_type;

                fn #factory_fn() -> UniquePtr<#verilator_type>;

                fn open_vcd(self: Pin<&mut #verilator_type>, path: &str);
                fn dump_vcd(self: Pin<&mut #verilator_type>, timestamp: u64);
                fn close_vcd(self: Pin<&mut #verilator_type>);

                fn eval(self: Pin<&mut #verilator_type>);
                fn final_eval(self: Pin<&mut #verilator_type>);

                fn get_clock(&self) -> u8;
                fn set_clock(self: Pin<&mut #verilator_type>, value: u8);
                fn get_reset(&self) -> u8;
                fn set_reset(self: Pin<&mut #verilator_type>, value: u8);
                fn get_rtc_clock(&self) -> u8;
                fn set_rtc_clock(self: Pin<&mut #verilator_type>, value: u8);

                fn get_debug_hart_in_id_valid(&self) -> u8;
                fn set_debug_hart_in_id_valid(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_id_bits(&self) -> u8;
                fn set_debug_hart_in_id_bits(self: Pin<&mut #verilator_type>, value: u8);

                fn get_debug_hart_in_bits_halt_valid(&self) -> u8;
                fn set_debug_hart_in_bits_halt_valid(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_bits_halt_bits(&self) -> u8;
                fn set_debug_hart_in_bits_halt_bits(self: Pin<&mut #verilator_type>, value: u8);

                fn get_debug_hart_in_bits_breakpoint_valid(&self) -> u8;
                fn set_debug_hart_in_bits_breakpoint_valid(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_bits_breakpoint_bits_pc(&self) -> u32;
                fn set_debug_hart_in_bits_breakpoint_bits_pc(self: Pin<&mut #verilator_type>, value: u32);

                fn get_debug_hart_in_bits_watchpoint_valid(&self) -> u8;
                fn set_debug_hart_in_bits_watchpoint_valid(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_bits_watchpoint_bits_addr(&self) -> u32;
                fn set_debug_hart_in_bits_watchpoint_bits_addr(self: Pin<&mut #verilator_type>, value: u32);

                fn get_debug_hart_in_bits_setPC_valid(&self) -> u8;
                fn set_debug_hart_in_bits_setPC_valid(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_bits_setPC_bits_pc(&self) -> u32;
                fn set_debug_hart_in_bits_setPC_bits_pc(self: Pin<&mut #verilator_type>, value: u32);

                fn get_debug_hart_in_bits_register_valid(&self) -> u8;
                fn set_debug_hart_in_bits_register_valid(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_bits_register_bits_reg(&self) -> u8;
                fn set_debug_hart_in_bits_register_bits_reg(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_bits_register_bits_write(&self) -> u8;
                fn set_debug_hart_in_bits_register_bits_write(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_hart_in_bits_register_bits_data(&self) -> u32;
                fn set_debug_hart_in_bits_register_bits_data(self: Pin<&mut #verilator_type>, value: u32);

                fn get_debug_mem_in_valid(&self) -> u8;
                fn set_debug_mem_in_valid(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_mem_in_ready(&self) -> u8;
                fn get_debug_mem_in_bits_addr(&self) -> u32;
                fn set_debug_mem_in_bits_addr(self: Pin<&mut #verilator_type>, value: u32);
                fn get_debug_mem_in_bits_write(&self) -> u8;
                fn set_debug_mem_in_bits_write(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_mem_in_bits_data(&self) -> u32;
                fn set_debug_mem_in_bits_data(self: Pin<&mut #verilator_type>, value: u32);
                fn get_debug_mem_in_bits_reqWidth(&self) -> u8;
                fn set_debug_mem_in_bits_reqWidth(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_mem_in_bits_instr(&self) -> u8;
                fn set_debug_mem_in_bits_instr(self: Pin<&mut #verilator_type>, value: u8);

                fn get_debug_mem_res_ready(&self) -> u8;
                fn set_debug_mem_res_ready(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_mem_res_valid(&self) -> u8;
                fn get_debug_mem_res_bits(&self) -> u32;

                fn get_debug_reg_res_ready(&self) -> u8;
                fn set_debug_reg_res_ready(self: Pin<&mut #verilator_type>, value: u8);
                fn get_debug_reg_res_valid(&self) -> u8;
                fn get_debug_reg_res_bits(&self) -> u32;

                fn get_debug_halted(&self) -> u8;

                #uart_bridge
            }
        }

        pub struct #struct_name {
            model: RefCell<UniquePtr<#ffi_ident::#verilator_type>>,
        }

        impl #struct_name {
            pub fn new() -> Self {
                Self {
                    model: RefCell::new(#ffi_ident::#factory_fn()),
                }
            }
        }

        impl SimulatorImpl for #struct_name {
            fn xlen(&self) -> u8 {
                #xlen
            }

            fn isa(&self) -> &'static str {
                #isa
            }

            fn name(&self) -> &'static str {
                #model_name
            }

            fn eval(&self) {
                self.model.borrow_mut().pin_mut().eval();
            }

            fn final_eval(&self) {
                self.model.borrow_mut().pin_mut().final_eval();
            }

            fn open_vcd(&self, path: &str) {
                self.model.borrow_mut().pin_mut().open_vcd(path);
            }

            fn dump_vcd(&self, timestamp: u64) {
                self.model.borrow_mut().pin_mut().dump_vcd(timestamp);
            }

            fn close_vcd(&self) {
                self.model.borrow_mut().pin_mut().close_vcd();
            }

            fn get_clock(&self) -> u8 {
                self.model.borrow().get_clock()
            }

            fn set_clock(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_clock(value);
            }

            fn get_reset(&self) -> u8 {
                self.model.borrow().get_reset()
            }

            fn set_reset(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_reset(value);
            }

            fn get_rtc_clock(&self) -> u8 {
                self.model.borrow().get_rtc_clock()
            }

            fn set_rtc_clock(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_rtc_clock(value);
            }

            fn get_debug_hart_in_id_valid(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_id_valid()
            }

            fn set_debug_hart_in_id_valid(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_hart_in_id_valid(value);
            }

            fn get_debug_hart_in_id_bits(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_id_bits()
            }

            fn set_debug_hart_in_id_bits(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_hart_in_id_bits(value);
            }

            fn get_debug_hart_in_bits_halt_valid(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_halt_valid()
            }

            fn set_debug_hart_in_bits_halt_valid(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_hart_in_bits_halt_valid(value);
            }

            fn get_debug_hart_in_bits_halt_bits(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_halt_bits()
            }

            fn set_debug_hart_in_bits_halt_bits(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_hart_in_bits_halt_bits(value);
            }

            fn get_debug_hart_in_bits_breakpoint_valid(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_breakpoint_valid()
            }

            fn set_debug_hart_in_bits_breakpoint_valid(&self, value: u8) {
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_breakpoint_valid(value);
            }

            fn get_debug_hart_in_bits_breakpoint_bits_pc(&self) -> u64 {
                self.model
                    .borrow()
                    .get_debug_hart_in_bits_breakpoint_bits_pc() as u64
            }

            fn set_debug_hart_in_bits_breakpoint_bits_pc(&self, value: u64) {
                let value = self.mask_to_u32(value);
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_breakpoint_bits_pc(value);
            }

            fn get_debug_hart_in_bits_watchpoint_valid(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_watchpoint_valid()
            }

            fn set_debug_hart_in_bits_watchpoint_valid(&self, value: u8) {
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_watchpoint_valid(value);
            }

            fn get_debug_hart_in_bits_watchpoint_bits_addr(&self) -> u64 {
                self.model
                    .borrow()
                    .get_debug_hart_in_bits_watchpoint_bits_addr() as u64
            }

            fn set_debug_hart_in_bits_watchpoint_bits_addr(&self, value: u64) {
                let value = self.mask_to_u32(value);
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_watchpoint_bits_addr(value);
            }

            fn get_debug_hart_in_bits_set_pc_valid(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_setPC_valid()
            }

            fn set_debug_hart_in_bits_set_pc_valid(&self, value: u8) {
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_setPC_valid(value);
            }

            fn get_debug_hart_in_bits_set_pc_bits_pc(&self) -> u64 {
                self.model.borrow().get_debug_hart_in_bits_setPC_bits_pc() as u64
            }

            fn set_debug_hart_in_bits_set_pc_bits_pc(&self, value: u64) {
                let value = self.mask_to_u32(value);
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_setPC_bits_pc(value);
            }

            fn get_debug_hart_in_bits_register_valid(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_register_valid()
            }

            fn set_debug_hart_in_bits_register_valid(&self, value: u8) {
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_register_valid(value);
            }

            fn get_debug_hart_in_bits_register_bits_reg(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_register_bits_reg()
            }

            fn set_debug_hart_in_bits_register_bits_reg(&self, value: u8) {
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_register_bits_reg(value);
            }

            fn get_debug_hart_in_bits_register_bits_write(&self) -> u8 {
                self.model.borrow().get_debug_hart_in_bits_register_bits_write()
            }

            fn set_debug_hart_in_bits_register_bits_write(&self, value: u8) {
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_register_bits_write(value);
            }

            fn get_debug_hart_in_bits_register_bits_data(&self) -> u64 {
                self.model.borrow().get_debug_hart_in_bits_register_bits_data() as u64
            }

            fn set_debug_hart_in_bits_register_bits_data(&self, value: u64) {
                let value = self.mask_to_u32(value);
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_hart_in_bits_register_bits_data(value);
            }

            fn get_debug_mem_in_valid(&self) -> u8 {
                self.model.borrow().get_debug_mem_in_valid()
            }

            fn set_debug_mem_in_valid(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_mem_in_valid(value);
            }

            fn get_debug_mem_in_ready(&self) -> u8 {
                self.model.borrow().get_debug_mem_in_ready()
            }

            fn get_debug_mem_in_bits_addr(&self) -> u64 {
                self.model.borrow().get_debug_mem_in_bits_addr() as u64
            }

            fn set_debug_mem_in_bits_addr(&self, value: u64) {
                let value = self.mask_to_u32(value);
                self.model.borrow_mut().pin_mut().set_debug_mem_in_bits_addr(value);
            }

            fn get_debug_mem_in_bits_write(&self) -> u8 {
                self.model.borrow().get_debug_mem_in_bits_write()
            }

            fn set_debug_mem_in_bits_write(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_mem_in_bits_write(value);
            }

            fn get_debug_mem_in_bits_data(&self) -> u64 {
                self.model.borrow().get_debug_mem_in_bits_data() as u64
            }

            fn set_debug_mem_in_bits_data(&self, value: u64) {
                let value = self.mask_to_u32(value);
                self.model.borrow_mut().pin_mut().set_debug_mem_in_bits_data(value);
            }

            fn get_debug_mem_in_bits_req_width(&self) -> u8 {
                self.model.borrow().get_debug_mem_in_bits_reqWidth()
            }

            fn set_debug_mem_in_bits_req_width(&self, value: u8) {
                self.model
                    .borrow_mut()
                    .pin_mut()
                    .set_debug_mem_in_bits_reqWidth(value);
            }

            fn get_debug_mem_in_bits_instr(&self) -> u8 {
                self.model.borrow().get_debug_mem_in_bits_instr()
            }

            fn set_debug_mem_in_bits_instr(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_mem_in_bits_instr(value);
            }

            fn get_debug_mem_res_ready(&self) -> u8 {
                self.model.borrow().get_debug_mem_res_ready()
            }

            fn set_debug_mem_res_ready(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_mem_res_ready(value);
            }

            fn get_debug_mem_res_valid(&self) -> u8 {
                self.model.borrow().get_debug_mem_res_valid()
            }

            fn get_debug_mem_res_bits(&self) -> u64 {
                self.model.borrow().get_debug_mem_res_bits() as u64
            }

            fn get_debug_reg_res_ready(&self) -> u8 {
                self.model.borrow().get_debug_reg_res_ready()
            }

            fn set_debug_reg_res_ready(&self, value: u8) {
                self.model.borrow_mut().pin_mut().set_debug_reg_res_ready(value);
            }

            fn get_debug_reg_res_valid(&self) -> u8 {
                self.model.borrow().get_debug_reg_res_valid()
            }

            fn get_debug_reg_res_bits(&self) -> u64 {
                self.model.borrow().get_debug_reg_res_bits() as u64
            }

            fn get_debug_halted(&self) -> u8 {
                self.model.borrow().get_debug_halted()
            }

            fn get_uart_0_txd(&self) -> u8 {
                #uart0_get
            }

            fn set_uart_0_rxd(&self, value: u8) {
                #uart0_set
            }

            fn get_uart_1_txd(&self) -> u8 {
                #uart1_get
            }

            fn set_uart_1_rxd(&self, value: u8) {
                #uart1_set
            }
        }
    };

    let cpp_header = generate_cpp_header(
        &model_identifier,
        &verilator_type.to_string(),
        &factory_fn.to_string(),
        num_uarts,
    );
    let mut cpp_header_file = File::create(header_path)?;
    cpp_header_file.write_all(cpp_header.as_bytes())?;

    Ok(GeneratedVerilator {
        model_name: model_name.to_string(),
        model_identifier,
        wrapper_name: struct_name.to_string(),
        rust: tokens,
        verilator_output,
    })
}

fn build_verilator(
    config_path: &Path,
    model_identifier: &str,
    with_monitors: bool,
) -> anyhow::Result<PathBuf> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?)
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_owned();
    let out_path = PathBuf::from(std::env::var("OUT_DIR")?)
        .join("verilator")
        .join(model_identifier);

    let sh = Shell::new().unwrap();
    sh.change_dir(manifest_dir);

    if with_monitors {
        cmd!(sh, "./mill -i svarog.runMain svarog.VerilogGenerator --simulator-debug-iface=true --with-monitors=true --target-dir={out_path} --config={config_path}").run()?;
    } else {
        cmd!(sh, "./mill -i svarog.runMain svarog.VerilogGenerator --simulator-debug-iface=true --target-dir={out_path} --config={config_path}").run()?;
    }

    let verilog_file = out_path.join("SvarogSoC.sv");
    let verilator_output = out_path.join("verilated");

    cmd!(
        sh,
        "verilator
        --prefix {model_identifier}
         -Wno-fatal
         -Wno-UNUSEDSIGNAL
         --cc
         --trace
         -O3
         --build
         --threads 4
         --no-assert
         -Mdir {verilator_output}
         {verilog_file}"
    )
    .run()?;

    Ok(verilator_output)
}

fn generate_cpp_header(
    model_identifier: &str,
    class_name: &str,
    factory_fn: &str,
    num_uarts: usize,
) -> String {
    let mut uart_accessors = String::new();
    for i in 0..num_uarts {
        uart_accessors.push_str(&format!(
            "    uint8_t get_uart_{i}_txd() const {{ return model_->io_gpio_{}_output; }}\n",
            i * 2 + 1
        ));
        uart_accessors.push_str(&format!(
            "    void set_uart_{i}_rxd(uint8_t value) {{ model_->io_gpio_{}_input = value; }}\n",
            i * 2
        ));
    }

    format!(
        r#"#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include "rust/cxx.h"

#include "verilated.h"
#include "verilated_vcd_c.h"

#include "{model_identifier}.h"

#ifndef SVAROG_SC_TIME_STAMP_DEFINED
#define SVAROG_SC_TIME_STAMP_DEFINED
inline double sc_time_stamp() {{
    return 0;
}}
#endif

namespace svarog::{model_identifier} {{

class {class_name} {{
public:
    {class_name}()
        : context_(std::make_unique<VerilatedContext>()),
          model_(std::make_unique<::{model_identifier}>(context_.get())) {{
        context_->commandArgs(0, static_cast<const char **>(nullptr));
        context_->traceEverOn(true);
    }}

    ~{class_name}() {{
        close_vcd();
        if (model_) {{
            model_->final();
        }}
    }}

    void open_vcd(rust::Str path) {{
        if (vcd_) {{
            vcd_->close();
        }}

        if (!vcd_) {{
            vcd_ = std::make_unique<VerilatedVcdC>();
            model_->trace(vcd_.get(), 99);
        }}

        vcd_->open(std::string(path).c_str());
    }}

    void dump_vcd(uint64_t timestamp) {{
        if (vcd_) {{
            vcd_->dump(timestamp);
        }}
    }}

    void close_vcd() {{
        if (vcd_) {{
            vcd_->close();
        }}
    }}

    void eval() {{ model_->eval(); }}
    void final_eval() {{ model_->final(); }}

    uint8_t get_clock() const {{ return model_->clock; }}
    void set_clock(uint8_t value) {{ model_->clock = value; }}
    uint8_t get_reset() const {{ return model_->reset; }}
    void set_reset(uint8_t value) {{ model_->reset = value; }}
    uint8_t get_rtc_clock() const {{ return model_->io_rtcClock; }}
    void set_rtc_clock(uint8_t value) {{ model_->io_rtcClock = value; }}

    uint8_t get_debug_hart_in_id_valid() const {{ return model_->io_debug_hart_in_id_valid; }}
    void set_debug_hart_in_id_valid(uint8_t value) {{ model_->io_debug_hart_in_id_valid = value; }}
    uint8_t get_debug_hart_in_id_bits() const {{ return model_->io_debug_hart_in_id_bits; }}
    void set_debug_hart_in_id_bits(uint8_t value) {{ model_->io_debug_hart_in_id_bits = value; }}

    uint8_t get_debug_hart_in_bits_halt_valid() const {{ return model_->io_debug_hart_in_bits_halt_valid; }}
    void set_debug_hart_in_bits_halt_valid(uint8_t value) {{ model_->io_debug_hart_in_bits_halt_valid = value; }}
    uint8_t get_debug_hart_in_bits_halt_bits() const {{ return model_->io_debug_hart_in_bits_halt_bits; }}
    void set_debug_hart_in_bits_halt_bits(uint8_t value) {{ model_->io_debug_hart_in_bits_halt_bits = value; }}

    uint8_t get_debug_hart_in_bits_breakpoint_valid() const {{ return model_->io_debug_hart_in_bits_breakpoint_valid; }}
    void set_debug_hart_in_bits_breakpoint_valid(uint8_t value) {{ model_->io_debug_hart_in_bits_breakpoint_valid = value; }}
    uint32_t get_debug_hart_in_bits_breakpoint_bits_pc() const {{ return model_->io_debug_hart_in_bits_breakpoint_bits_pc; }}
    void set_debug_hart_in_bits_breakpoint_bits_pc(uint32_t value) {{ model_->io_debug_hart_in_bits_breakpoint_bits_pc = value; }}

    uint8_t get_debug_hart_in_bits_watchpoint_valid() const {{ return model_->io_debug_hart_in_bits_watchpoint_valid; }}
    void set_debug_hart_in_bits_watchpoint_valid(uint8_t value) {{ model_->io_debug_hart_in_bits_watchpoint_valid = value; }}
    uint32_t get_debug_hart_in_bits_watchpoint_bits_addr() const {{ return model_->io_debug_hart_in_bits_watchpoint_bits_addr; }}
    void set_debug_hart_in_bits_watchpoint_bits_addr(uint32_t value) {{ model_->io_debug_hart_in_bits_watchpoint_bits_addr = value; }}

    uint8_t get_debug_hart_in_bits_setPC_valid() const {{ return model_->io_debug_hart_in_bits_setPC_valid; }}
    void set_debug_hart_in_bits_setPC_valid(uint8_t value) {{ model_->io_debug_hart_in_bits_setPC_valid = value; }}
    uint32_t get_debug_hart_in_bits_setPC_bits_pc() const {{ return model_->io_debug_hart_in_bits_setPC_bits_pc; }}
    void set_debug_hart_in_bits_setPC_bits_pc(uint32_t value) {{ model_->io_debug_hart_in_bits_setPC_bits_pc = value; }}

    uint8_t get_debug_hart_in_bits_register_valid() const {{ return model_->io_debug_hart_in_bits_register_valid; }}
    void set_debug_hart_in_bits_register_valid(uint8_t value) {{ model_->io_debug_hart_in_bits_register_valid = value; }}
    uint8_t get_debug_hart_in_bits_register_bits_reg() const {{ return model_->io_debug_hart_in_bits_register_bits_reg; }}
    void set_debug_hart_in_bits_register_bits_reg(uint8_t value) {{ model_->io_debug_hart_in_bits_register_bits_reg = value; }}
    uint8_t get_debug_hart_in_bits_register_bits_write() const {{ return model_->io_debug_hart_in_bits_register_bits_write; }}
    void set_debug_hart_in_bits_register_bits_write(uint8_t value) {{ model_->io_debug_hart_in_bits_register_bits_write = value; }}
    uint32_t get_debug_hart_in_bits_register_bits_data() const {{ return model_->io_debug_hart_in_bits_register_bits_data; }}
    void set_debug_hart_in_bits_register_bits_data(uint32_t value) {{ model_->io_debug_hart_in_bits_register_bits_data = value; }}

    uint8_t get_debug_mem_in_valid() const {{ return model_->io_debug_mem_in_valid; }}
    void set_debug_mem_in_valid(uint8_t value) {{ model_->io_debug_mem_in_valid = value; }}
    uint8_t get_debug_mem_in_ready() const {{ return model_->io_debug_mem_in_ready; }}
    uint32_t get_debug_mem_in_bits_addr() const {{ return model_->io_debug_mem_in_bits_addr; }}
    void set_debug_mem_in_bits_addr(uint32_t value) {{ model_->io_debug_mem_in_bits_addr = value; }}
    uint8_t get_debug_mem_in_bits_write() const {{ return model_->io_debug_mem_in_bits_write; }}
    void set_debug_mem_in_bits_write(uint8_t value) {{ model_->io_debug_mem_in_bits_write = value; }}
    uint32_t get_debug_mem_in_bits_data() const {{ return model_->io_debug_mem_in_bits_data; }}
    void set_debug_mem_in_bits_data(uint32_t value) {{ model_->io_debug_mem_in_bits_data = value; }}
    uint8_t get_debug_mem_in_bits_reqWidth() const {{ return model_->io_debug_mem_in_bits_reqWidth; }}
    void set_debug_mem_in_bits_reqWidth(uint8_t value) {{ model_->io_debug_mem_in_bits_reqWidth = value; }}
    uint8_t get_debug_mem_in_bits_instr() const {{ return model_->io_debug_mem_in_bits_instr; }}
    void set_debug_mem_in_bits_instr(uint8_t value) {{ model_->io_debug_mem_in_bits_instr = value; }}

    uint8_t get_debug_mem_res_ready() const {{ return model_->io_debug_mem_res_ready; }}
    void set_debug_mem_res_ready(uint8_t value) {{ model_->io_debug_mem_res_ready = value; }}
    uint8_t get_debug_mem_res_valid() const {{ return model_->io_debug_mem_res_valid; }}
    uint32_t get_debug_mem_res_bits() const {{ return model_->io_debug_mem_res_bits; }}

    uint8_t get_debug_reg_res_ready() const {{ return model_->io_debug_reg_res_ready; }}
    void set_debug_reg_res_ready(uint8_t value) {{ model_->io_debug_reg_res_ready = value; }}
    uint8_t get_debug_reg_res_valid() const {{ return model_->io_debug_reg_res_valid; }}
    uint32_t get_debug_reg_res_bits() const {{ return model_->io_debug_reg_res_bits; }}

    uint8_t get_debug_halted() const {{ return model_->io_debug_halted; }}

{uart_accessors}private:
    std::unique_ptr<VerilatedContext> context_;
    std::unique_ptr<::{model_identifier}> model_;
    std::unique_ptr<VerilatedVcdC> vcd_;
}};

inline std::unique_ptr<{class_name}> {factory_fn}() {{
    return std::make_unique<{class_name}>();
}}

}} // namespace svarog::{model_identifier}
"#
    )
}

fn to_pascal_case(value: &str) -> String {
    value
        .split('-')
        .map(|segment| {
            let mut chars = segment.chars();
            match chars.next() {
                Some(first) => first.to_uppercase().chain(chars).collect(),
                None => String::new(),
            }
        })
        .collect()
}
