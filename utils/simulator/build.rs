use anyhow::{Context, Result};
use std::fs;
use std::path::{Path, PathBuf};
use xshell::{Shell, cmd};

#[derive(Debug, Clone, serde::Deserialize)]
struct IoConfig {
    #[serde(rename = "type")]
    io_type: String,
    #[serde(default)]
    #[allow(dead_code)] // May be used in future for named accessors or debugging
    name: String,
}

#[derive(Debug, Clone, serde::Deserialize)]
struct SocConfig {
    #[serde(default)]
    io: Vec<IoConfig>,
}

#[derive(Debug, Clone)]
struct ModelInfo {
    name: String,         // "svg-micro"
    yaml_path: PathBuf,   // "configs/svg-micro.yaml"
    identifier: String,   // "svg_micro"
    namespace: String,    // "svg_micro"
    enum_variant: String, // "SvgMicro"
    num_uarts: usize,     // Number of UARTs in config
}

impl ModelInfo {
    fn from_yaml_path(path: PathBuf) -> Result<Self> {
        let name = path
            .file_stem()
            .and_then(|s| s.to_str())
            .ok_or_else(|| anyhow::anyhow!("Invalid config filename: {:?}", path))?
            .to_string();

        let identifier = name.replace('-', "_");
        let enum_variant = to_pascal_case(&name);

        // Parse YAML to count UARTs
        let yaml_content =
            fs::read_to_string(&path).context(format!("Failed to read config file: {:?}", path))?;
        let config: SocConfig = serde_yaml::from_str(&yaml_content)
            .context(format!("Failed to parse YAML config: {:?}", path))?;

        let num_uarts = config.io.iter().filter(|io| io.io_type == "uart").count();

        Ok(ModelInfo {
            name,
            yaml_path: path,
            namespace: identifier.clone(),
            identifier,
            enum_variant,
            num_uarts,
        })
    }
}

fn to_pascal_case(s: &str) -> String {
    s.split('-')
        .map(|word| {
            let mut chars = word.chars();
            match chars.next() {
                Some(first) => first.to_uppercase().chain(chars).collect(),
                None => String::new(),
            }
        })
        .collect()
}

fn discover_models(workspace_root: &Path) -> Result<Vec<ModelInfo>> {
    let pattern = workspace_root.join("configs/*.yaml");
    let mut models = Vec::new();

    for entry in glob::glob(pattern.to_str().unwrap())? {
        let path = entry?;
        models.push(ModelInfo::from_yaml_path(path)?);
    }

    if models.is_empty() {
        anyhow::bail!("No config files found in configs/ directory");
    }

    // Sort for deterministic builds
    models.sort_by(|a, b| a.name.cmp(&b.name));

    Ok(models)
}

fn generate_verilog(sh: &Shell, workspace_root: &Path, model: &ModelInfo) -> Result<()> {
    let output_dir = workspace_root.join(format!("target/generated/{}", model.name));
    fs::create_dir_all(&output_dir)?;

    let config_path = &model.yaml_path;

    sh.change_dir(workspace_root);
    cmd!(
        sh,
        "./mill -i svarog.runMain svarog.VerilogGenerator --simulator-debug-iface=true --target-dir={output_dir} --config={config_path}"
    )
    .run()
    .context(format!("Failed to generate Verilog for model: {}", model.name))?;

    Ok(())
}

fn run_verilator(sh: &Shell, workspace_root: &Path, model: &ModelInfo) -> Result<()> {
    let verilog_file = workspace_root.join(format!("target/generated/{}/SvarogSoC.sv", model.name));
    let verilator_out_dir = workspace_root.join(format!("target/verilator/{}", model.name));

    fs::create_dir_all(&verilator_out_dir)?;

    let verilator_stamp = verilator_out_dir.join("verilator_build.stamp");

    // Check if we need to run Verilator
    let need_verilator = !verilator_stamp.exists()
        || verilog_file.metadata()?.modified()? > verilator_stamp.metadata()?.modified()?;

    if !need_verilator {
        println!(
            "cargo:warning=Verilator build for {} is up-to-date",
            model.name
        );
        return Ok(());
    }

    println!("cargo:warning=Running Verilator for model: {}", model.name);

    let prefix = format!("VSvarogSoC_{}", model.identifier);

    sh.change_dir(workspace_root);
    cmd!(
        sh,
        "verilator
         --prefix {prefix}
         -Wno-fatal
         -Wno-UNUSEDSIGNAL
         --cc
         --trace
         -O3
         --build
         -Mdir {verilator_out_dir}
         {verilog_file}"
    )
    .run()
    .context(format!("Failed to run Verilator for model: {}", model.name))?;

    // Create stamp file
    fs::write(&verilator_stamp, "")?;

    Ok(())
}

fn generate_cpp_wrapper(workspace_root: &Path, model: &ModelInfo) -> Result<()> {
    let template_h = include_str!("cpp/templates/wrapper.h.template");
    let template_cpp = include_str!("cpp/templates/wrapper.cpp.template");

    let output_dir = workspace_root.join(format!(
        "utils/simulator/cpp/generated/{}",
        model.identifier
    ));
    fs::create_dir_all(&output_dir)?;

    // Generate UART accessors based on config
    let uart_header = generate_uart_accessors_header(model.num_uarts);
    let uart_impl = generate_uart_accessors_impl(model.num_uarts);

    // Generate header
    let header = template_h
        .replace("{{CONFIG_NAMESPACE}}", &model.namespace)
        .replace("{{CONFIG_ID}}", &model.identifier)
        .replace("{{UART_ACCESSORS_HEADER}}", &uart_header);
    fs::write(output_dir.join("wrapper.h"), header)?;

    // Generate implementation
    let implementation = template_cpp
        .replace("{{CONFIG_NAMESPACE}}", &model.namespace)
        .replace("{{CONFIG_ID}}", &model.identifier)
        .replace("{{UART_ACCESSORS_IMPL}}", &uart_impl);
    fs::write(output_dir.join("wrapper.cpp"), implementation)?;

    Ok(())
}

fn compile_wrapper(workspace_root: &Path, model: &ModelInfo, verilator_root: &str) -> Result<()> {
    let wrapper_cpp = workspace_root.join(format!(
        "utils/simulator/cpp/generated/{}/wrapper.cpp",
        model.identifier
    ));
    let verilator_out_dir = workspace_root.join(format!("target/verilator/{}", model.name));
    let verilator_include = PathBuf::from(verilator_root.trim()).join("include");

    let bridge_path = format!("src/ffi/{}.rs", model.identifier);

    cxx_build::bridge(&bridge_path)
        .file(&wrapper_cpp)
        .include(&verilator_out_dir)
        .include(&verilator_include)
        .include(workspace_root)
        .flag_if_supported("-std=gnu++17")
        .flag_if_supported("-DVL_THREADED")
        .flag_if_supported("-w")
        .compile(&format!("verilator_wrapper_{}", model.identifier));

    Ok(())
}

fn link_verilator_libs(workspace_root: &Path, model: &ModelInfo) -> Result<()> {
    let verilator_dir = workspace_root.join(format!("target/verilator/{}", model.name));

    println!("cargo:rustc-link-search=native={}", verilator_dir.display());

    // Each config's Verilator build produces its own VSvarogSoC library
    // Because we used --prefix, they have different symbol names
    println!(
        "cargo:rustc-link-lib=static=VSvarogSoC_{}",
        model.identifier
    );
    println!("cargo:rustc-link-lib=static=verilated");

    Ok(())
}

fn generate_uart_accessors(num_uarts: usize) -> String {
    if num_uarts == 0 {
        return String::new();
    }

    let mut accessors = String::from("\n        // UART signals (dynamically generated)\n");

    for i in 0..num_uarts {
        accessors.push_str(&format!("        fn get_uart_{}_txd(&self) -> u8;\n", i));
        accessors.push_str(&format!(
            "        fn set_uart_{}_rxd(self: Pin<&mut VerilatorModel>, value: u8);\n",
            i
        ));
    }

    accessors
}

fn generate_uart_accessors_header(num_uarts: usize) -> String {
    if num_uarts == 0 {
        return String::from("    // No UART interfaces in this config\n");
    }

    let mut header = String::from("    // UART signals (dynamically generated from config)\n");

    for i in 0..num_uarts {
        // GPIO pins are assigned sequentially: UART 0 uses pins 0,1; UART 1 uses pins 2,3; etc.
        let txd_pin = i * 2;
        let rxd_pin = i * 2 + 1;

        header.push_str(&format!(
            "    // UART {} - GPIO pins {},{}\n",
            i, txd_pin, rxd_pin
        ));
        header.push_str(&format!("    uint8_t get_uart_{}_txd() const;\n", i));
        header.push_str(&format!("    void set_uart_{}_rxd(uint8_t value);\n\n", i));
    }

    header
}

fn generate_uart_accessors_impl(num_uarts: usize) -> String {
    if num_uarts == 0 {
        return String::from("// No UART interfaces in this config\n\n");
    }

    let mut impl_code = String::from("// UART signals (dynamically generated from config)\n");

    for i in 0..num_uarts {
        // GPIO pins are assigned sequentially: each UART uses 2 pins (rxd, txd)
        // UART 0 -> pins 0,1; UART 1 -> pins 2,3; etc.
        let rxd_pin = i * 2; // First pin is rxd (input to SoC)
        let txd_pin = i * 2 + 1; // Second pin is txd (output from SoC)

        impl_code.push_str(&format!(
            "// UART {} accessors (GPIO pins {}, {})\n",
            i, rxd_pin, txd_pin
        ));

        // TXD: Read the output value from the SoC
        impl_code.push_str(&format!(
            "uint8_t VerilatorModel::get_uart_{}_txd() const {{\n",
            i
        ));
        impl_code.push_str(&format!("    return model_->io_gpio_{}_output;\n", txd_pin));
        impl_code.push_str("}\n\n");

        // RXD: Write input value to the SoC
        impl_code.push_str(&format!(
            "void VerilatorModel::set_uart_{}_rxd(uint8_t value) {{\n",
            i
        ));
        impl_code.push_str(&format!("    model_->io_gpio_{}_input = value;\n", rxd_pin));
        impl_code.push_str("}\n\n");
    }

    impl_code
}

fn generate_bridge_module(workspace_root: &Path, model: &ModelInfo) -> Result<()> {
    let output_dir = workspace_root.join("utils/simulator/src/ffi");
    fs::create_dir_all(&output_dir)?;

    let module_content = format!(
        r#"// Auto-generated by build.rs for model: {}

#[cxx::bridge(namespace = "svarog::{}")]
pub mod ffi {{
    #[allow(dead_code)]
    unsafe extern "C++" {{
        include!("utils/simulator/cpp/generated/{}/wrapper.h");

        type VerilatorModel;

        // Factory function
        fn create_verilator_model() -> UniquePtr<VerilatorModel>;

        // VCD tracing
        fn open_vcd(self: Pin<&mut VerilatorModel>, path: &str);
        fn dump_vcd(self: Pin<&mut VerilatorModel>, timestamp: u64);
        fn close_vcd(self: Pin<&mut VerilatorModel>);

        // Simulation control
        fn eval(self: Pin<&mut VerilatorModel>);
        fn final_eval(self: Pin<&mut VerilatorModel>);

        // Clock and reset
        fn get_clock(&self) -> u8;
        fn set_clock(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_reset(&self) -> u8;
        fn set_reset(self: Pin<&mut VerilatorModel>, value: u8);

        // Debug hart interface - ID routing
        fn get_debug_hart_in_id_valid(&self) -> u8;
        fn set_debug_hart_in_id_valid(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_id_bits(&self) -> u8;
        fn set_debug_hart_in_id_bits(self: Pin<&mut VerilatorModel>, value: u8);

        // Debug hart interface - Halt control
        fn get_debug_hart_in_bits_halt_valid(&self) -> u8;
        fn set_debug_hart_in_bits_halt_valid(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_bits_halt_bits(&self) -> u8;
        fn set_debug_hart_in_bits_halt_bits(self: Pin<&mut VerilatorModel>, value: u8);

        // Debug hart interface - Breakpoint
        fn get_debug_hart_in_bits_breakpoint_valid(&self) -> u8;
        fn set_debug_hart_in_bits_breakpoint_valid(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_bits_breakpoint_bits_pc(&self) -> u32;
        fn set_debug_hart_in_bits_breakpoint_bits_pc(self: Pin<&mut VerilatorModel>, value: u32);

        // Debug hart interface - Watchpoint
        fn get_debug_hart_in_bits_watchpoint_valid(&self) -> u8;
        fn set_debug_hart_in_bits_watchpoint_valid(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_bits_watchpoint_bits_addr(&self) -> u32;
        fn set_debug_hart_in_bits_watchpoint_bits_addr(self: Pin<&mut VerilatorModel>, value: u32);

        // Debug hart interface - Set PC
        fn get_debug_hart_in_bits_setPC_valid(&self) -> u8;
        fn set_debug_hart_in_bits_setPC_valid(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_bits_setPC_bits_pc(&self) -> u32;
        fn set_debug_hart_in_bits_setPC_bits_pc(self: Pin<&mut VerilatorModel>, value: u32);

        // Debug hart interface - Register access
        fn get_debug_hart_in_bits_register_valid(&self) -> u8;
        fn set_debug_hart_in_bits_register_valid(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_bits_register_bits_reg(&self) -> u8;
        fn set_debug_hart_in_bits_register_bits_reg(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_bits_register_bits_write(&self) -> u8;
        fn set_debug_hart_in_bits_register_bits_write(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_hart_in_bits_register_bits_data(&self) -> u32;
        fn set_debug_hart_in_bits_register_bits_data(self: Pin<&mut VerilatorModel>, value: u32);

        // Debug memory interface - Request
        fn get_debug_mem_in_valid(&self) -> u8;
        fn set_debug_mem_in_valid(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_mem_in_ready(&self) -> u8;
        fn get_debug_mem_in_bits_addr(&self) -> u32;
        fn set_debug_mem_in_bits_addr(self: Pin<&mut VerilatorModel>, value: u32);
        fn get_debug_mem_in_bits_write(&self) -> u8;
        fn set_debug_mem_in_bits_write(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_mem_in_bits_data(&self) -> u32;
        fn set_debug_mem_in_bits_data(self: Pin<&mut VerilatorModel>, value: u32);
        fn get_debug_mem_in_bits_reqWidth(&self) -> u8;
        fn set_debug_mem_in_bits_reqWidth(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_mem_in_bits_instr(&self) -> u8;
        fn set_debug_mem_in_bits_instr(self: Pin<&mut VerilatorModel>, value: u8);

        // Debug memory interface - Response
        fn get_debug_mem_res_ready(&self) -> u8;
        fn set_debug_mem_res_ready(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_mem_res_valid(&self) -> u8;
        fn get_debug_mem_res_bits(&self) -> u32;

        // Debug register interface - Response
        fn get_debug_reg_res_ready(&self) -> u8;
        fn set_debug_reg_res_ready(self: Pin<&mut VerilatorModel>, value: u8);
        fn get_debug_reg_res_valid(&self) -> u8;
        fn get_debug_reg_res_bits(&self) -> u32;

        // Debug status
        fn get_debug_halted(&self) -> u8;
{}
    }}
}}
"#,
        model.name,
        model.namespace,
        model.identifier,
        generate_uart_accessors(model.num_uarts)
    );

    fs::write(
        output_dir.join(format!("{}.rs", model.identifier)),
        module_content,
    )?;

    Ok(())
}

fn generate_model_registry(workspace_root: &Path, models: &[ModelInfo]) -> Result<()> {
    let output_dir = workspace_root.join("utils/simulator/src");

    let enum_variants = models
        .iter()
        .map(|m| format!("    {},", m.enum_variant))
        .collect::<Vec<_>>()
        .join("\n");

    let from_name_arms = models
        .iter()
        .map(|m| {
            format!(
                r#"            "{}" => Some(ModelId::{}),
"#,
                m.name, m.enum_variant
            )
        })
        .collect::<String>();

    let name_arms = models
        .iter()
        .map(|m| {
            format!(
                r#"            ModelId::{} => "{}",
"#,
                m.enum_variant, m.name
            )
        })
        .collect::<String>();

    let module_imports = models
        .iter()
        .map(|m| format!("    pub mod {};", m.identifier))
        .collect::<Vec<_>>()
        .join("\n");

    let available_models_list = models
        .iter()
        .map(|m| format!("    ModelId::{},", m.enum_variant))
        .collect::<Vec<_>>()
        .join("\n");

    let registry_content = format!(
        r#"// Auto-generated by build.rs - DO NOT EDIT

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ModelId {{
{}
}}

impl ModelId {{
    pub fn name(&self) -> &'static str {{
        match self {{
{}        }}
    }}

    pub fn from_name(name: &str) -> Option<Self> {{
        match name {{
{}            _ => None,
        }}
    }}
}}

impl Default for ModelId {{
    fn default() -> Self {{
        // Return the first model (alphabetically)
        AVAILABLE_MODELS[0]
    }}
}}

pub const AVAILABLE_MODELS: &[ModelId] = &[
{}
];

// Generated FFI bridge modules
pub mod ffi {{
{}
}}
"#,
        enum_variants, name_arms, from_name_arms, available_models_list, module_imports
    );

    fs::write(output_dir.join("generated.rs"), registry_content)?;

    Ok(())
}

fn main() -> Result<()> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let workspace_root = manifest_dir
        .parent()
        .and_then(|p| p.parent())
        .ok_or_else(|| anyhow::anyhow!("Could not determine workspace root"))?
        .to_path_buf();

    let sh = Shell::new()?;

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=../../configs/");
    println!("cargo:rerun-if-changed=../../src/main/");
    println!("cargo:rerun-if-changed=cpp/templates/");

    // Discover models
    let models = discover_models(&workspace_root)?;
    println!(
        "cargo:warning=Found {} model(s): {}",
        models.len(),
        models
            .iter()
            .map(|m| m.name.as_str())
            .collect::<Vec<_>>()
            .join(", ")
    );

    // Get Verilator root
    let verilator_root = String::from_utf8(
        std::process::Command::new("verilator")
            .arg("--getenv")
            .arg("VERILATOR_ROOT")
            .output()
            .context("Failed to get VERILATOR_ROOT")?
            .stdout,
    )?;

    // Build each model
    for model in &models {
        println!("cargo:warning=Processing model: {}", model.name);

        // 1. Generate Verilog
        generate_verilog(&sh, &workspace_root, model)?;

        // 2. Run Verilator
        run_verilator(&sh, &workspace_root, model)?;

        // 3. Generate C++ wrapper
        generate_cpp_wrapper(&workspace_root, model)?;

        // 4. Generate Rust bridge module
        generate_bridge_module(&workspace_root, model)?;

        // 5. Compile wrapper
        compile_wrapper(&workspace_root, model, &verilator_root)?;

        // 6. Link Verilator libraries
        link_verilator_libs(&workspace_root, model)?;
    }

    // Generate model registry
    generate_model_registry(&workspace_root, &models)?;

    Ok(())
}
