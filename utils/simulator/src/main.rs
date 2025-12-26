use anyhow::{Context, Result};
use camino::Utf8PathBuf;
use clap::Parser;
use simulator::{ModelId, Simulator};

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
    vcd: Utf8PathBuf,

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

fn main() -> Result<()> {
    let args = Args::parse();

    if args.list_models {
        println!("Available models:");
        for model in Simulator::available_models() {
            println!("  - {}", model.name());
        }
        return Ok(());
    }

    let binary = args
        .binary
        .ok_or_else(|| anyhow::anyhow!("BINARY argument is required"))?;

    // Determine which model to use
    let model_id = if let Some(model_name) = &args.model {
        ModelId::from_name(model_name)
            .ok_or_else(|| anyhow::anyhow!("Unknown model: {}", model_name))?
    } else {
        // Use default model (first one)
        ModelId::default()
    };

    println!("Using model: {}", model_id.name());

    // Create simulator
    let sim = Simulator::new(model_id).context("Failed to create simulator")?;

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
    let result = sim
        .run_with_entry_point(args.vcd.as_std_path(), args.max_cycles, entry_point)
        .context("Simulation failed")?;

    println!("\nSimulation complete!");
    println!("VCD trace: {}", args.vcd);

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
