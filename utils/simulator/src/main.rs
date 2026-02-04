use anyhow::{Context, Result};
use camino::Utf8PathBuf;
use clap::Parser;
use simulator::{Backend, Simulator};
use std::io::Write;

#[derive(Parser)]
#[command(name = "svarog-sim")]
#[command(about = "Standalone Svarog SoC simulator")]
#[command(version)]
struct Args {
    /// Path to ELF binary to execute
    #[arg(value_name = "BINARY")]
    binary: Option<Utf8PathBuf>,

    /// Backend to use
    #[arg(long, default_value = "verilator")]
    backend: String,

    /// Model to use
    #[arg(short, long)]
    model: Option<String>,

    /// VCD output file
    #[arg(long)]
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

fn draw_progress(current: usize, max: usize) {
    let bar_width = 40usize;
    let capped = current.min(max);
    let filled = if max == 0 {
        bar_width
    } else {
        capped.saturating_mul(bar_width) / max
    };
    let percent = if max == 0 {
        100usize
    } else {
        capped.saturating_mul(100) / max
    };

    let mut bar = String::with_capacity(bar_width);
    for idx in 0..bar_width {
        bar.push(if idx < filled { '#' } else { '-' });
    }

    eprint!(
        "\r[{bar}] {capped:>7}/{max:>7} cycles {percent:>3}%",
        bar = bar,
        capped = capped,
        max = max,
        percent = percent
    );
    std::io::stderr().flush().ok();
}

fn main() -> Result<()> {
    let args = Args::parse();

    let backend = Backend::from_name(&args.backend)
        .ok_or_else(|| anyhow::anyhow!("Unknown backend: {}", args.backend))?;

    if args.list_models {
        println!("Available models for backend {}:", backend.name());
        for model in Simulator::available_models(backend) {
            println!("  - {}", model);
        }
        return Ok(());
    }

    let binary = args
        .binary
        .ok_or_else(|| anyhow::anyhow!("BINARY argument is required"))?;

    let model_name = if let Some(model_name) = &args.model {
        model_name.clone()
    } else {
        let models = Simulator::available_models(backend);
        if models.is_empty() {
            return Err(anyhow::anyhow!(
                "No models available for backend {}",
                backend.name()
            ));
        }
        models[0].to_string()
    };

    println!("Using backend: {}", backend.name());
    println!("Using model: {}", model_name);

    // Create simulator
    let sim = Simulator::new(backend, &model_name).context("Failed to create simulator")?;

    // Enable UART console if requested
    if let Some(uart_index) = args.uart_console {
        sim.enable_uart_console(uart_index);
    }

    // Detect file type and load appropriately
    let is_raw_binary = binary.extension().map(|ext| ext == "bin").unwrap_or(false);

    let entry_point = if is_raw_binary {
        // Raw binary file
        let load_addr = args.load_addr.unwrap_or(0x80000000);
        println!("Loading raw binary: {}", binary);
        println!("  Load address: 0x{:08x}", load_addr);

        let entry = sim
            .load_raw_binary(&binary, load_addr, args.entry_point, args.watchpoint_addr)
            .context("Failed to load raw binary")?;

        println!("  Entry point:  0x{:08x}", entry);
        entry
    } else {
        // ELF file
        println!("Loading ELF binary: {}", binary);
        sim.load_binary(&binary, args.watchpoint.as_deref())
            .context("Failed to load ELF binary")?;
        0x80000000 // Default entry point for ELF
    };

    // Run simulation
    println!("Running simulation (max {} cycles)...", args.max_cycles);
    let show_progress = args.uart_console.is_none();
    let mut last_seen_cycle = 0usize;
    let mut last_drawn_cycle = 0usize;
    if show_progress {
        draw_progress(0, args.max_cycles);
    }

    let result = sim
        .run_with_entry_point_and_progress(
            args.vcd.as_ref().map(|p| p.as_std_path()),
            args.max_cycles,
            entry_point,
            |cycle| {
                if !show_progress {
                    return;
                }
                last_seen_cycle = cycle;
                if cycle == args.max_cycles || cycle.saturating_sub(last_drawn_cycle) >= 256 {
                    draw_progress(cycle, args.max_cycles);
                    last_drawn_cycle = cycle;
                }
            },
        )
        .context("Simulation failed")?;
    if show_progress {
        if last_drawn_cycle != last_seen_cycle {
            draw_progress(last_seen_cycle, args.max_cycles);
        }
        eprintln!();
    }

    println!("\nSimulation complete!");

    if let Some(exit_code) = result.exit_code {
        println!("Exit code: {}", exit_code);

        // Dump register file
        println!("\nRegister state:");
        for i in 0..32 {
            let val = result.regs.get(i);
            if val != 0 {
                println!("  x{:2} = 0x{:08x}", i, val);
            }
        }

        // Exit with the same code as the simulation
        std::process::exit(exit_code as i32);
    }

    Ok(())
}
