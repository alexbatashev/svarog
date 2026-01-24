use anyhow::{Context, Result};
use camino::Utf8PathBuf;
use clap::Parser;
use simulator::arc::{Signal, SignalType, StaticHierarchy};
use simulator::{list_arcilator_models, Simulator, UartDecoder};
use std::ffi::CStr;
use std::io::Write;
use std::path::Path;

#[derive(Parser)]
#[command(name = "svarog-sim")]
#[command(about = "Standalone Svarog SoC simulator")]
#[command(version)]
struct Args {
    /// Path to ELF binary to execute
    #[arg(value_name = "BINARY")]
    binary: Option<Utf8PathBuf>,

    /// Model to use
    #[arg(short, long)]
    model: Option<String>,

    /// VCD output file
    #[arg(long, default_value = "trace.vcd")]
    vcd: Option<Utf8PathBuf>,

    /// Maximum simulation cycles
    #[arg(long, default_value = "100000")]
    max_cycles: usize,

    /// Watchpoint symbol (e.g., "tohost") for ELF binaries
    #[arg(long)]
    watchpoint: Option<String>,

    /// Watchpoint address (e.g., 0x80001000) for raw binaries
    #[arg(long, value_parser = parse_hex)]
    watchpoint_addr: Option<u32>,

    /// Load address for raw binary files (default: 0x80000000)
    #[arg(long, value_parser = parse_hex)]
    load_addr: Option<u32>,

    /// Entry point / PC for raw binary files (default: same as load address)
    #[arg(long, value_parser = parse_hex)]
    entry_point: Option<u32>,

    /// Enable UART console output (0 or 1)
    #[arg(long)]
    uart_console: Option<usize>,

    /// List available models and exit
    #[arg(long)]
    list_models: bool,
}

fn parse_hex(s: &str) -> Result<u32, std::num::ParseIntError> {
    if let Some(hex) = s.strip_prefix("0x") {
        u32::from_str_radix(hex, 16)
    } else {
        s.parse()
    }
}

fn read_elf_entry(path: &Path) -> Result<u32> {
    let data = std::fs::read(path)?;
    let elf = elf::ElfBytes::<elf::endian::AnyEndian>::minimal_parse(data.as_slice())?;
    Ok(elf.ehdr.e_entry as u32)
}

fn signal_name(signal: &simulator::arc::Signal) -> &str {
    unsafe { CStr::from_ptr(signal.name) }
        .to_str()
        .unwrap_or("")
}

fn find_signal<'a>(
    signals: &'a [simulator::arc::Signal],
    name: &str,
) -> Option<&'a simulator::arc::Signal> {
    signals.iter().find(|signal| signal_name(signal) == name)
}

fn require_signal<'a>(
    signals: &'a [simulator::arc::Signal],
    name: &str,
) -> Result<&'a simulator::arc::Signal> {
    find_signal(signals, name).ok_or_else(|| anyhow::anyhow!("Missing required signal: {}", name))
}

fn write_signal_u8(sim: &mut dyn Simulator, signal: &simulator::arc::Signal, value: u8) {
    let offset = signal.offset as usize;
    sim.state_buf_mut()[offset] = value;
}

fn read_signal_u8(sim: &dyn Simulator, signal: &simulator::arc::Signal) -> u8 {
    sim.state_buf()[signal.offset as usize]
}

fn read_signal_u32(sim: &dyn Simulator, signal: &simulator::arc::Signal) -> u32 {
    let offset = signal.offset as usize;
    let bytes = sim.state_buf();
    if offset + 4 > bytes.len() {
        return 0;
    }
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

fn read_u32_at_offset(sim: &dyn Simulator, offset: u32) -> u32 {
    let offset = offset as usize;
    let bytes = sim.state_buf();
    if offset + 4 > bytes.len() {
        return 0;
    }
    u32::from_le_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

fn read_tcm_word(sim: &dyn Simulator, mem_signal: &Signal, addr: u32) -> Option<u32> {
    const TCM_BASE: u32 = 0x8000_0000;
    if addr < TCM_BASE {
        return None;
    }
    let index = (addr - TCM_BASE) / 4;
    let offset = mem_signal.offset + index * 4;
    Some(read_u32_at_offset(sim, offset))
}

fn write_signal_u32(sim: &mut dyn Simulator, signal: &simulator::arc::Signal, value: u32) {
    let offset = signal.offset as usize;
    let bytes = value.to_le_bytes();
    let buf = sim.state_buf_mut();
    if offset + 4 <= buf.len() {
        buf[offset..offset + 4].copy_from_slice(&bytes);
    }
}

fn find_signal_in_hierarchy<'a>(
    hierarchy: &'a simulator::arc::StaticHierarchy,
    target: &str,
) -> Option<&'a simulator::arc::Signal> {
    for signal in hierarchy.states {
        if signal_name(signal) == target {
            return Some(signal);
        }
    }
    for child in hierarchy.children {
        if let Some(found) = find_signal_in_hierarchy(child, target) {
            return Some(found);
        }
    }
    None
}

fn collect_signals<'a>(hierarchy: &'a StaticHierarchy, out: &mut Vec<&'a Signal>) {
    out.extend(hierarchy.states.iter());
    for child in hierarchy.children {
        collect_signals(child, out);
    }
}

fn collect_uart_candidates<'a>(hierarchy: &'a StaticHierarchy) -> Vec<&'a Signal> {
    let mut signals = Vec::new();
    collect_signals(hierarchy, &mut signals);
    let mut uart_signals: Vec<&Signal> = signals
        .into_iter()
        .filter(|signal| {
            let name = signal_name(signal);
            name.contains("uart") && name.contains("txd")
        })
        .collect();
    uart_signals.sort_by_key(|signal| signal_name(signal).to_string());
    uart_signals
}

fn collect_uart_debug_signals<'a>(hierarchy: &'a StaticHierarchy) -> Vec<&'a Signal> {
    let mut signals = Vec::new();
    collect_signals(hierarchy, &mut signals);
    let mut uart_signals: Vec<&Signal> = signals
        .into_iter()
        .filter(|signal| {
            let name = signal_name(signal);
            name.contains("uart")
                && (name.contains("tx")
                    || name.contains("rx")
                    || name.contains("valid")
                    || name.contains("ready"))
        })
        .collect();
    uart_signals.sort_by_key(|signal| signal_name(signal).to_string());
    uart_signals.truncate(12);
    uart_signals
}

fn collect_uart_reset_clock<'a>(hierarchy: &'a StaticHierarchy) -> Vec<&'a Signal> {
    let mut signals = Vec::new();
    collect_signals(hierarchy, &mut signals);
    let mut uart_signals: Vec<&Signal> = signals
        .into_iter()
        .filter(|signal| {
            let name = signal_name(signal);
            name.contains("uartCore") && (name.contains("reset") || name.contains("clock"))
        })
        .collect();
    uart_signals.sort_by_key(|signal| signal_name(signal).to_string());
    uart_signals.truncate(8);
    uart_signals
}

fn main() -> Result<()> {
    let args = Args::parse();

    if args.list_models {
        println!("Available models:");
        for model in list_arcilator_models() {
            println!("  - {}", model.name());
        }
        return Ok(());
    }

    let binary = args
        .binary
        .ok_or_else(|| anyhow::anyhow!("BINARY argument is required"))?;

    let mut models = list_arcilator_models();
    let mut sim = if let Some(model_name) = &args.model {
        let pos = models
            .iter()
            .position(|model| model.name() == model_name)
            .ok_or_else(|| anyhow::anyhow!("Unknown model: {}", model_name))?;
        models.swap_remove(pos)
    } else {
        models
            .into_iter()
            .next()
            .ok_or_else(|| anyhow::anyhow!("No models available"))?
    };
    let binary_path = binary.into_std_path_buf();
    let entry_point = read_elf_entry(binary_path.as_path()).context("Failed to read ELF entry")?;
    sim.load_binary(binary_path.as_path(), args.watchpoint.as_deref())
        .context("Failed to load ELF binary")?;

    let io_signals = sim.io();
    let clock_signal = require_signal(io_signals, "clock")?;
    let debug_halted_signal = find_signal(io_signals, "io_debug_halted");
    let hart_id_valid_signal = require_signal(io_signals, "io_debug_hart_in_id_valid")?;
    let hart_id_bits_signal = require_signal(io_signals, "io_debug_hart_in_id_bits")?;
    let halt_valid_signal = require_signal(io_signals, "io_debug_hart_in_bits_halt_valid")?;
    let halt_bits_signal = require_signal(io_signals, "io_debug_hart_in_bits_halt_bits")?;
    let setpc_valid_signal = require_signal(io_signals, "io_debug_hart_in_bits_setPC_valid")?;
    let setpc_bits_signal = require_signal(io_signals, "io_debug_hart_in_bits_setPC_bits_pc")?;
    let rtc_clock_signal = find_signal(io_signals, "io_rtcClock");
    let reset_signal = require_signal(io_signals, "reset")?;
    let debug_mem_res_ready_signal = find_signal(io_signals, "io_debug_mem_res_ready");
    let debug_reg_res_ready_signal = find_signal(io_signals, "io_debug_reg_res_ready");
    let debug_mem_in_valid_signal = find_signal(io_signals, "io_debug_mem_in_valid");
    let debug_mem_in_ready_signal = find_signal(io_signals, "io_debug_mem_in_ready");
    let debug_mem_in_write_signal = find_signal(io_signals, "io_debug_mem_in_bits_write");
    let debug_mem_in_addr_signal = find_signal(io_signals, "io_debug_mem_in_bits_addr");
    let debug_mem_in_data_signal = find_signal(io_signals, "io_debug_mem_in_bits_data");
    let debug_mem_in_width_signal = find_signal(io_signals, "io_debug_mem_in_bits_reqWidth");
    let debug_mem_in_instr_signal = find_signal(io_signals, "io_debug_mem_in_bits_instr");
    let debug_mem_res_valid_signal = find_signal(io_signals, "io_debug_mem_res_valid");
    let debug_mem_res_bits_signal = find_signal(io_signals, "io_debug_mem_res_bits");

    let mut rtc_counter: u64 = 0;
    let mut rtc_level: u8 = 0;
    let mut tick = |sim: &mut Box<dyn Simulator>| -> Result<()> {
        if let Some(rtc_signal) = rtc_clock_signal {
            rtc_counter += 1;
            if rtc_counter >= 50 {
                rtc_counter = 0;
                rtc_level ^= 1;
                write_signal_u8(&mut **sim, rtc_signal, rtc_level);
            }
        }
        write_signal_u8(&mut **sim, clock_signal, 0);
        sim.step()?;
        write_signal_u8(&mut **sim, clock_signal, 1);
        sim.step()?;
        Ok(())
    };

    let mut debug_read_word = |sim: &mut Box<dyn Simulator>,
                               addr: u32,
                               tick_fn: &mut dyn FnMut(&mut Box<dyn Simulator>) -> Result<()>|
     -> Result<Option<u32>> {
        let (
            Some(in_valid),
            Some(in_ready),
            Some(in_write),
            Some(in_addr),
            Some(in_data),
            Some(in_width),
            Some(in_instr),
            Some(res_valid),
            Some(res_bits),
        ) = (
            debug_mem_in_valid_signal,
            debug_mem_in_ready_signal,
            debug_mem_in_write_signal,
            debug_mem_in_addr_signal,
            debug_mem_in_data_signal,
            debug_mem_in_width_signal,
            debug_mem_in_instr_signal,
            debug_mem_res_valid_signal,
            debug_mem_res_bits_signal,
        )
        else {
            return Ok(None);
        };

        for _ in 0..1000 {
            write_signal_u32(&mut **sim, in_addr, addr);
            write_signal_u8(&mut **sim, in_write, 0);
            write_signal_u32(&mut **sim, in_data, 0);
            write_signal_u8(&mut **sim, in_width, 2);
            write_signal_u8(&mut **sim, in_instr, 0);
            write_signal_u8(&mut **sim, in_valid, 1);
            let ready = read_signal_u8(&**sim, in_ready) != 0;
            tick_fn(sim)?;
            if ready {
                break;
            }
        }

        write_signal_u8(&mut **sim, in_valid, 0);
        write_signal_u8(&mut **sim, in_write, 0);

        for _ in 0..1000 {
            if read_signal_u8(&**sim, res_valid) != 0 {
                let value = read_signal_u32(&**sim, res_bits);
                return Ok(Some(value));
            }
            tick_fn(sim)?;
        }

        Ok(None)
    };

    for signal in io_signals {
        let name = signal_name(signal);
        if name.starts_with("io_gpio_") && name.ends_with("_input") {
            write_signal_u8(&mut *sim, signal, 1);
        }
    }

    write_signal_u8(&mut *sim, hart_id_valid_signal, 1);
    write_signal_u8(&mut *sim, hart_id_bits_signal, 0);
    if let Some(signal) = debug_mem_res_ready_signal {
        write_signal_u8(&mut *sim, signal, 1);
    }
    if let Some(signal) = debug_reg_res_ready_signal {
        write_signal_u8(&mut *sim, signal, 1);
    }
    if let Some(signal) = debug_mem_in_valid_signal {
        write_signal_u8(&mut *sim, signal, 0);
    }
    if let Some(signal) = debug_mem_in_write_signal {
        write_signal_u8(&mut *sim, signal, 0);
    }
    if let Some(signal) = debug_mem_in_addr_signal {
        write_signal_u32(&mut *sim, signal, 0);
    }
    if let Some(signal) = debug_mem_in_data_signal {
        write_signal_u32(&mut *sim, signal, 0);
    }
    if let Some(signal) = debug_mem_in_width_signal {
        write_signal_u8(&mut *sim, signal, 0);
    }
    if let Some(signal) = debug_mem_in_instr_signal {
        write_signal_u8(&mut *sim, signal, 0);
    }

    write_signal_u8(&mut *sim, reset_signal, 1);
    for _ in 0..2 {
        tick(&mut sim)?;
    }
    write_signal_u8(&mut *sim, reset_signal, 0);
    tick(&mut sim)?;

    write_signal_u8(&mut *sim, hart_id_valid_signal, 1);
    write_signal_u8(&mut *sim, hart_id_bits_signal, 0);
    write_signal_u32(&mut *sim, setpc_bits_signal, entry_point);
    write_signal_u8(&mut *sim, setpc_valid_signal, 1);
    tick(&mut sim)?;
    write_signal_u8(&mut *sim, setpc_valid_signal, 0);
    tick(&mut sim)?;

    if let Some(signal) = debug_mem_in_valid_signal {
        write_signal_u8(&mut *sim, signal, 0);
    }
    write_signal_u8(&mut *sim, halt_bits_signal, 0);
    write_signal_u8(&mut *sim, halt_valid_signal, 1);
    tick(&mut sim)?;

    write_signal_u8(&mut *sim, hart_id_valid_signal, 0);
    write_signal_u8(&mut *sim, halt_valid_signal, 0);
    for _ in 0..10 {
        tick(&mut sim)?;
    }

    let hierarchy = sim.hierarchy();
    let uart_candidates = collect_uart_candidates(hierarchy);
    let uart_debug_signals = collect_uart_debug_signals(hierarchy);
    let uart_reset_clock = collect_uart_reset_clock(hierarchy);
    let mut uart_prev: Vec<u8> = uart_candidates
        .iter()
        .map(|signal| read_signal_u8(&*sim, signal))
        .collect();
    let mut uart_transitions = vec![0u64; uart_candidates.len()];
    let mut uart_debug_prev: Vec<u32> = uart_debug_signals
        .iter()
        .map(|signal| read_signal_u32(&*sim, signal))
        .collect();
    let mut uart_debug_transitions = vec![0u64; uart_debug_signals.len()];
    let mut uart_reset_prev: Vec<u8> = uart_reset_clock
        .iter()
        .map(|signal| read_signal_u8(&*sim, signal))
        .collect();
    let mut uart_reset_transitions = vec![0u64; uart_reset_clock.len()];
    if !uart_candidates.is_empty() {
        println!("UART candidates:");
        for (idx, signal) in uart_candidates.iter().enumerate() {
            println!("  [{}] {}", idx, signal_name(signal));
        }
    }
    if !uart_debug_signals.is_empty() {
        println!("UART debug signals:");
        for (idx, signal) in uart_debug_signals.iter().enumerate() {
            println!("  [{}] {}", idx, signal_name(signal));
        }
    }
    if !uart_reset_clock.is_empty() {
        println!("UART reset/clock signals:");
        for (idx, signal) in uart_reset_clock.iter().enumerate() {
            println!("  [{}] {}", idx, signal_name(signal));
        }
    }
    let mut uart = args
        .uart_console
        .map(|index| -> Result<(UartDecoder, &simulator::arc::Signal)> {
            if index < uart_candidates.len() {
                let signal = uart_candidates[index];
                println!("UART console on {}", signal_name(signal));
                return Ok((UartDecoder::new(), signal));
            }
            let output_name = format!("io_gpio_{}_output", index);
            let output_signal = require_signal(io_signals, &output_name)?;
            println!("UART console on {}", output_name);
            Ok((UartDecoder::new(), output_signal))
        })
        .transpose()?;
    let reset_internal = find_signal_in_hierarchy(hierarchy, "reset");
    let mem_probe = find_signal_in_hierarchy(hierarchy, "mem_ext");
    let pc_signal = find_signal_in_hierarchy(hierarchy, "pc_reg")
        .or_else(|| find_signal_in_hierarchy(hierarchy, "pendingInst_pc"));
    let mut pc_candidates = Vec::new();
    collect_signals(hierarchy, &mut pc_candidates);
    let mut pc_candidates: Vec<&Signal> = pc_candidates
        .into_iter()
        .filter(|signal| {
            let name = signal_name(signal);
            signal.num_bits == 32
                && (signal.ty == SignalType::Register || signal.ty == SignalType::Wire)
                && name.contains("pc")
        })
        .collect();
    pc_candidates.sort_by_key(|signal| signal_name(signal).to_string());
    pc_candidates.truncate(12);
    let mut pc_values: Vec<u32> = pc_candidates
        .iter()
        .map(|signal| read_signal_u32(&*sim, signal))
        .collect();
    if let Some(signal) = pc_signal {
        println!("PC probe signal: {}", signal_name(signal));
        println!("PC start: 0x{:08x}", read_signal_u32(&*sim, signal));
    } else {
        println!("PC probe signal not found");
    }
    if !pc_candidates.is_empty() {
        println!("PC candidates (first {}):", pc_candidates.len());
        for (signal, value) in pc_candidates.iter().zip(pc_values.iter()) {
            println!("  {} = 0x{:08x}", signal_name(signal), value);
        }
    }
    if let Some(signal) = reset_internal {
        println!("Reset probe signal: {}", signal_name(signal));
        println!("Reset start: {}", read_signal_u8(&*sim, signal));
    }
    if let Some(signal) = mem_probe {
        println!("Memory probe signal: {}", signal_name(signal));
        println!(
            "Memory probe word0: 0x{:08x}",
            read_signal_u32(&*sim, signal)
        );
        let word1 = read_u32_at_offset(&*sim, signal.offset + 4);
        println!("Memory probe word1: 0x{:08x}", word1);
    }
    if let Ok(Some(status)) = debug_read_word(&mut sim, 0x0010_0004, &mut tick) {
        println!("UART0 status via debug: 0x{:08x}", status);
    } else {
        println!("UART0 status via debug: <unavailable>");
    }

    let mut gpio_outputs = Vec::new();
    let mut gpio_activity = [false; 4];
    let mut gpio_prev = [0u8; 4];
    for index in 0..4 {
        let output_name = format!("io_gpio_{}_output", index);
        let write_name = format!("io_gpio_{}_write", index);
        if let Some(output_signal) = find_signal(io_signals, &output_name) {
            let write_signal = find_signal(io_signals, &write_name);
            gpio_prev[index] = read_signal_u8(&*sim, output_signal);
            gpio_outputs.push((index, output_signal, write_signal));
        }
    }

    let watchpoint_enabled = args.watchpoint.is_some() || args.watchpoint_addr.is_some();
    let mut hit_watchpoint = false;
    for cycle in 0..args.max_cycles {
        tick(&mut sim)?;

        if let Some((decoder, uart_signal)) = uart.as_mut() {
            let txd = read_signal_u8(&*sim, uart_signal);
            if let Some(byte) = decoder.process(txd) {
                print!("{}", byte as char);
                std::io::stdout().flush().ok();
            }
        }

        for (idx, signal) in uart_candidates.iter().enumerate() {
            let value = read_signal_u8(&*sim, signal);
            if value != uart_prev[idx] {
                uart_transitions[idx] += 1;
                uart_prev[idx] = value;
            }
        }
        for (idx, signal) in uart_debug_signals.iter().enumerate() {
            let value = read_signal_u32(&*sim, signal);
            if value != uart_debug_prev[idx] {
                uart_debug_transitions[idx] += 1;
                uart_debug_prev[idx] = value;
            }
        }
        for (idx, signal) in uart_reset_clock.iter().enumerate() {
            let value = read_signal_u8(&*sim, signal);
            if value != uart_reset_prev[idx] {
                uart_reset_transitions[idx] += 1;
                uart_reset_prev[idx] = value;
            }
        }

        if !gpio_outputs.is_empty() {
            for (index, output_signal, write_signal) in &gpio_outputs {
                let output_value = read_signal_u8(&*sim, output_signal);
                if output_value != gpio_prev[*index] {
                    if !gpio_activity[*index] {
                        gpio_activity[*index] = true;
                        let write_note = write_signal
                            .map(|signal| read_signal_u8(&*sim, signal))
                            .unwrap_or(1);
                        println!(
                            "GPIO {} activity: output={} write={}",
                            index, output_value, write_note
                        );
                    }
                    gpio_prev[*index] = output_value;
                }
            }
        }

        if cycle % 100 == 0 {
            if let Some(signal) = pc_signal {
                let pc = read_signal_u32(&*sim, signal);
                println!("PC @{}: 0x{:08x}", cycle, pc);
                if let Some(mem_signal) = mem_probe {
                    if let Some(instr) = read_tcm_word(&*sim, mem_signal, pc) {
                        println!("instr @{}: 0x{:08x}", cycle, instr);
                    }
                }
            }
            if let Some(signal) = debug_halted_signal {
                println!("debug_halted @{}: {}", cycle, read_signal_u8(&*sim, signal));
            }
            if let Some(signal) = reset_internal {
                println!("reset @{}: {}", cycle, read_signal_u8(&*sim, signal));
            }
            if let Some(signal) = mem_probe {
                println!(
                    "mem[0] @{}: 0x{:08x}",
                    cycle,
                    read_signal_u32(&*sim, signal)
                );
            }
            if !pc_candidates.is_empty() {
                for (idx, signal) in pc_candidates.iter().enumerate() {
                    let value = read_signal_u32(&*sim, signal);
                    if value != pc_values[idx] {
                        println!(
                            "PC change @{}: {} 0x{:08x} -> 0x{:08x}",
                            cycle,
                            signal_name(signal),
                            pc_values[idx],
                            value
                        );
                        pc_values[idx] = value;
                    }
                }
            }
        }

        if watchpoint_enabled {
            if let Some(signal) = debug_halted_signal {
                if read_signal_u8(&*sim, signal) != 0 {
                    hit_watchpoint = true;
                    break;
                }
            }
        }
    }

    if uart.is_some() && !gpio_activity.iter().any(|active| *active) {
        println!("No GPIO activity detected during run");
    }
    if !uart_candidates.is_empty() {
        println!("UART transition counts:");
        for (idx, signal) in uart_candidates.iter().enumerate() {
            println!(
                "  [{}] {}: {}",
                idx,
                signal_name(signal),
                uart_transitions[idx]
            );
        }
    }
    if !uart_debug_signals.is_empty() {
        println!("UART debug transition counts:");
        for (idx, signal) in uart_debug_signals.iter().enumerate() {
            println!(
                "  [{}] {}: {}",
                idx,
                signal_name(signal),
                uart_debug_transitions[idx]
            );
        }
    }
    if !uart_reset_clock.is_empty() {
        println!("UART reset/clock transition counts:");
        for (idx, signal) in uart_reset_clock.iter().enumerate() {
            println!(
                "  [{}] {}: {}",
                idx,
                signal_name(signal),
                uart_reset_transitions[idx]
            );
        }
    }

    if watchpoint_enabled {
        if hit_watchpoint {
            println!("Watchpoint hit");
        } else {
            println!("Max cycles reached without hitting watchpoint");
        }
    } else {
        println!("Max cycles reached");
    }

    Ok(())
}
