use serde::Deserialize;
use std::collections::HashSet;
use std::fmt::Write as FmtWrite;
use std::fs;
use std::io::{self, Write};
use std::path::Path;

// ============================================================================
// JSON Model Parsing
// ============================================================================

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum StateType {
    Input,
    Output,
    Register,
    Wire,
    Memory,
}

#[derive(Debug, Clone, Deserialize)]
pub struct StateInfo {
    pub name: String,
    pub offset: u32,
    #[serde(rename = "numBits")]
    pub num_bits: u32,
    #[serde(rename = "type")]
    pub ty: StateType,
    #[serde(default)]
    pub stride: Option<u32>,
    #[serde(default)]
    pub depth: Option<u32>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RawModelInfo {
    pub name: String,
    #[serde(rename = "numStateBytes")]
    pub num_state_bytes: u32,
    #[serde(rename = "initialFnSym", default)]
    pub initial_fn_sym: String,
    #[serde(rename = "finalFnSym", default)]
    pub final_fn_sym: String,
    pub states: Vec<StateInfo>,
}

#[derive(Debug, Clone)]
pub struct StateHierarchy {
    pub name: String,
    pub states: Vec<StateInfo>,
    pub children: Vec<StateHierarchy>,
}

#[derive(Debug, Clone)]
pub struct ModelInfo {
    pub name: String,
    pub num_state_bytes: u32,
    pub initial_fn_sym: String,
    pub final_fn_sym: String,
    pub states: Vec<StateInfo>,
    pub io: Vec<StateInfo>,
    pub hierarchy: Vec<StateHierarchy>,
}

// ============================================================================
// Hierarchy Grouping
// ============================================================================

fn group_state_by_hierarchy(states: Vec<StateInfo>) -> (Vec<StateInfo>, Vec<StateHierarchy>) {
    let mut local_state = Vec::new();
    let mut hierarchies = Vec::new();
    let mut remainder = Vec::new();
    let mut used_names: HashSet<String> = HashSet::new();

    fn uniquify(name: &str, used_names: &mut HashSet<String>) -> String {
        let mut unique_name = name.to_string();
        if used_names.contains(&unique_name) {
            let mut i = 0;
            loop {
                let candidate = format!("{}_{}", name, i);
                if !used_names.contains(&candidate) {
                    unique_name = candidate;
                    break;
                }
                i += 1;
            }
        }
        used_names.insert(unique_name.clone());
        unique_name
    }

    for mut state in states {
        if state.name.is_empty() || !state.name.contains('/') {
            state.name = uniquify(&state.name, &mut used_names);
            local_state.push(state);
        } else {
            remainder.push(state);
        }
    }

    while !remainder.is_empty() {
        let prefix = remainder[0].name.split('/').next().unwrap().to_string();
        let prefix_with_slash = format!("{}/", prefix);

        let mut left = Vec::new();
        let mut substates = Vec::new();

        for mut state in remainder {
            if let Some(rest) = state.name.strip_prefix(&prefix_with_slash) {
                state.name = rest.to_string();
                substates.push(state);
            } else {
                left.push(state);
            }
        }

        remainder = left;
        let (hierarchy_states, hierarchy_children) = group_state_by_hierarchy(substates);
        let unique_prefix = uniquify(&prefix, &mut used_names);
        hierarchies.push(StateHierarchy {
            name: unique_prefix,
            states: hierarchy_states,
            children: hierarchy_children,
        });
    }

    (local_state, hierarchies)
}

// ============================================================================
// Model Loading
// ============================================================================

pub fn load_models<P: AsRef<Path>>(state_json: P) -> io::Result<Vec<ModelInfo>> {
    let content = fs::read_to_string(state_json)?;
    let raw_models: Vec<RawModelInfo> = serde_json::from_str(&content)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;

    let mut models = Vec::new();

    for raw in raw_models {
        let mut io_states = Vec::new();
        let mut internal_states = Vec::new();

        for state in raw.states {
            match state.ty {
                StateType::Input | StateType::Output => io_states.push(state),
                _ => internal_states.push(state),
            }
        }

        let (hierarchy_states, hierarchy_children) = group_state_by_hierarchy(internal_states);
        let hierarchy = vec![StateHierarchy {
            name: "internal".to_string(),
            states: hierarchy_states,
            children: hierarchy_children,
        }];

        models.push(ModelInfo {
            name: raw.name,
            num_state_bytes: raw.num_state_bytes,
            initial_fn_sym: raw.initial_fn_sym,
            final_fn_sym: raw.final_fn_sym,
            states: Vec::new(), // Not used after processing
            io: io_states,
            hierarchy,
        });
    }

    Ok(models)
}

// ============================================================================
// Rust Code Generation
// ============================================================================

const RUST_KEYWORDS: &[&str] = &[
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move",
    "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true",
    "type", "unsafe", "use", "where", "while", "abstract", "become", "box", "do", "final",
    "macro", "override", "priv", "typeof", "unsized", "virtual", "yield", "try",
];

fn clean_name(name: &str) -> String {
    let mut result = String::new();
    for c in name.chars() {
        if c.is_alphanumeric() || c == '_' {
            result.push(c);
        } else {
            result.push('_');
        }
    }

    // Ensure name starts with a letter or underscore
    if result.is_empty() || result.chars().next().unwrap().is_numeric() {
        result = format!("_{}", result);
    }

    // Handle Rust keywords
    if RUST_KEYWORDS.contains(&result.as_str()) {
        result = format!("r#{}", result);
    }

    result
}

fn state_rust_type_nonmemory(num_bits: u32) -> &'static str {
    match num_bits {
        0..=8 => "u8",
        9..=16 => "u16",
        17..=32 => "u32",
        33..=64 => "u64",
        _ => "u128", // For very large values, we'll use u128 or byte arrays
    }
}

fn state_rust_type(state: &StateInfo) -> String {
    if state.ty == StateType::Memory {
        let elem_type = state_rust_type_nonmemory(state.num_bits);
        let depth = state.depth.unwrap_or(1);
        format!("[{}; {}]", elem_type, depth)
    } else {
        state_rust_type_nonmemory(state.num_bits).to_string()
    }
}

fn signal_type_variant(ty: StateType) -> &'static str {
    match ty {
        StateType::Input => "Input",
        StateType::Output => "Output",
        StateType::Register => "Register",
        StateType::Wire => "Wire",
        StateType::Memory => "Memory",
    }
}

fn format_signal(state: &StateInfo) -> String {
    let stride = state.stride.unwrap_or(0);
    let depth = state.depth.unwrap_or(0);
    // Escape any special characters in the name for safe inclusion in concat!
    let escaped_name = state.name.replace('\\', "\\\\").replace('"', "\\\"");
    format!(
        "Signal {{ name: concat!(\"{}\", \"\\0\").as_ptr().cast(), offset: {}, num_bits: {}, ty: SignalType::{}, stride: {}, depth: {} }}",
        escaped_name,
        state.offset,
        state.num_bits,
        signal_type_variant(state.ty),
        stride,
        depth
    )
}

fn format_hierarchy(hierarchy: &StateHierarchy, indent_level: usize) -> String {
    let indent = "    ".repeat(indent_level);
    let inner_indent = "    ".repeat(indent_level + 1);

    let mut states_code = String::new();
    if !hierarchy.states.is_empty() {
        states_code.push_str("&[\n");
        for state in &hierarchy.states {
            writeln!(states_code, "{}{},", inner_indent, format_signal(state)).unwrap();
        }
        write!(states_code, "{}]", indent).unwrap();
    } else {
        states_code.push_str("&[]");
    }

    let mut children_code = String::new();
    if !hierarchy.children.is_empty() {
        children_code.push_str("&[\n");
        for child in &hierarchy.children {
            writeln!(
                children_code,
                "{}{},",
                inner_indent,
                format_hierarchy(child, indent_level + 1)
            )
            .unwrap();
        }
        write!(children_code, "{}]", indent).unwrap();
    } else {
        children_code.push_str("&[]");
    }

    let escaped_name = hierarchy.name.replace('\\', "\\\\").replace('"', "\\\"");
    format!(
        "StaticHierarchy {{ name: concat!(\"{}\", \"\\0\").as_ptr().cast(), num_states: {}, num_children: {}, states: {}, children: {} }}",
        escaped_name,
        hierarchy.states.len(),
        hierarchy.children.len(),
        states_code,
        children_code
    )
}

pub fn render_rust_code(models: &[ModelInfo], view_depth: i32) -> String {
    let mut output = String::new();

    // Header
    writeln!(output, "// Auto-generated by arcgen - do not edit manually").unwrap();
    writeln!(output).unwrap();
    writeln!(output, "use crate::arc::{{Signal, SignalType, Hierarchy}};").unwrap();
    writeln!(output).unwrap();

    // Static hierarchy structure (for compile-time data)
    writeln!(output, "#[derive(Debug)]").unwrap();
    writeln!(output, "pub struct StaticHierarchy {{").unwrap();
    writeln!(output, "    pub name: *const std::ffi::c_char,").unwrap();
    writeln!(output, "    pub num_states: u32,").unwrap();
    writeln!(output, "    pub num_children: u32,").unwrap();
    writeln!(output, "    pub states: &'static [Signal],").unwrap();
    writeln!(output, "    pub children: &'static [StaticHierarchy],").unwrap();
    writeln!(output, "}}").unwrap();
    writeln!(output).unwrap();
    writeln!(output, "// SAFETY: StaticHierarchy contains only raw pointers to static strings").unwrap();
    writeln!(output, "unsafe impl Sync for StaticHierarchy {{}}").unwrap();
    writeln!(output).unwrap();

    for model in models {
        // Ensure IO names are unique and don't conflict with 'state'
        let mut reserved: HashSet<String> = HashSet::new();
        reserved.insert("state".to_string());

        let io: Vec<_> = model
            .io
            .iter()
            .map(|s| {
                let mut state = s.clone();
                if reserved.contains(&state.name) {
                    state.name = format!("{}_", state.name);
                }
                reserved.insert(state.name.clone());
                state
            })
            .collect();

        // External function declarations
        writeln!(output, "extern \"C\" {{").unwrap();
        if !model.initial_fn_sym.is_empty() {
            writeln!(
                output,
                "    fn {}_initial(state: *mut std::ffi::c_void);",
                model.name
            )
            .unwrap();
        }
        writeln!(
            output,
            "    fn {}_eval(state: *mut std::ffi::c_void);",
            model.name
        )
        .unwrap();
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();

        // Layout struct
        writeln!(output, "/// Layout information for {}", model.name).unwrap();
        writeln!(output, "pub struct {}Layout;", model.name).unwrap();
        writeln!(output).unwrap();
        writeln!(output, "impl {}Layout {{", model.name).unwrap();
        writeln!(
            output,
            "    pub const NAME: &'static str = \"{}\";",
            model.name
        )
        .unwrap();
        writeln!(output, "    pub const NUM_STATES: usize = {};", io.len()).unwrap();
        writeln!(
            output,
            "    pub const NUM_STATE_BYTES: usize = {};",
            model.num_state_bytes
        )
        .unwrap();
        writeln!(output).unwrap();

        // IO signals
        writeln!(
            output,
            "    pub const IO: [Signal; {}] = [",
            io.len()
        )
        .unwrap();
        for s in &io {
            writeln!(output, "        {},", format_signal(s)).unwrap();
        }
        writeln!(output, "    ];").unwrap();
        writeln!(output).unwrap();

        // Hierarchy
        if let Some(hierarchy) = model.hierarchy.first() {
            writeln!(
                output,
                "    pub const HIERARCHY: StaticHierarchy = {};",
                format_hierarchy(hierarchy, 2)
            )
            .unwrap();
        }
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();

        // View struct for internal hierarchy
        if let Some(hierarchy) = model.hierarchy.first() {
            // Generate view structs for each hierarchy level
            fn generate_view_structs(
                output: &mut String,
                hierarchy: &StateHierarchy,
                depth: i32,
                model_name: &str,
            ) {
                let struct_name = format!("{}{}View", model_name, clean_name(&hierarchy.name));

                writeln!(output, "#[allow(non_snake_case)]").unwrap();
                writeln!(output, "pub struct {}<'a> {{", struct_name).unwrap();

                for state in &hierarchy.states {
                    let clean = clean_name(&state.name);
                    let ty = state_rust_type(state);
                    writeln!(output, "    pub {}: &'a mut {},", clean, ty).unwrap();
                }

                if depth != 0 {
                    for child in &hierarchy.children {
                        let clean = clean_name(&child.name);
                        let child_struct_name =
                            format!("{}{}View", model_name, clean_name(&child.name));
                        writeln!(output, "    pub {}: {}<'a>,", clean, child_struct_name).unwrap();
                    }
                }

                writeln!(output, "}}").unwrap();
                writeln!(output).unwrap();

                // Recursively generate child view structs
                if depth != 0 {
                    for child in &hierarchy.children {
                        generate_view_structs(output, child, depth - 1, model_name);
                    }
                }
            }

            generate_view_structs(&mut output, hierarchy, view_depth, &model.name);
        }

        // Main View struct
        writeln!(output, "/// View into {} state", model.name).unwrap();
        writeln!(output, "#[allow(non_snake_case)]").unwrap();
        writeln!(output, "pub struct {}View<'a> {{", model.name).unwrap();
        for s in &io {
            let clean = clean_name(&s.name);
            let ty = state_rust_type(s);
            writeln!(output, "    pub {}: &'a mut {},", clean, ty).unwrap();
        }
        if let Some(hierarchy) = model.hierarchy.first() {
            let internal_view_name =
                format!("{}{}View", model.name, clean_name(&hierarchy.name));
            writeln!(
                output,
                "    pub {}: {}<'a>,",
                clean_name(&hierarchy.name),
                internal_view_name
            )
            .unwrap();
        }
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();

        // View constructor
        writeln!(output, "impl<'a> {}View<'a> {{", model.name).unwrap();
        writeln!(
            output,
            "    /// Create a new view into the state buffer"
        )
        .unwrap();
        writeln!(output, "    ///").unwrap();
        writeln!(output, "    /// # Safety").unwrap();
        writeln!(
            output,
            "    /// The state buffer must be at least {} bytes",
            model.num_state_bytes
        )
        .unwrap();
        writeln!(
            output,
            "    pub unsafe fn new(state: &'a mut [u8]) -> Self {{"
        )
        .unwrap();
        writeln!(
            output,
            "        debug_assert!(state.len() >= {});",
            model.num_state_bytes
        )
        .unwrap();
        writeln!(output, "        Self {{").unwrap();

        for s in &io {
            let clean = clean_name(&s.name);
            let ty = state_rust_type(s);
            writeln!(
                output,
                "            {}: &mut *(state.as_mut_ptr().add({}) as *mut {}),",
                clean, s.offset, ty
            )
            .unwrap();
        }

        if let Some(hierarchy) = model.hierarchy.first() {
            fn generate_view_init(
                output: &mut String,
                hierarchy: &StateHierarchy,
                depth: i32,
                model_name: &str,
                indent_level: usize,
            ) {
                let indent = "    ".repeat(indent_level);
                let inner_indent = "    ".repeat(indent_level + 1);
                let struct_name = format!("{}{}View", model_name, clean_name(&hierarchy.name));

                writeln!(output, "{}{} {{", indent, struct_name).unwrap();

                for state in &hierarchy.states {
                    let clean = clean_name(&state.name);
                    let ty = state_rust_type(state);
                    writeln!(
                        output,
                        "{}{}: &mut *(state.as_mut_ptr().add({}) as *mut {}),",
                        inner_indent, clean, state.offset, ty
                    )
                    .unwrap();
                }

                if depth != 0 {
                    for child in &hierarchy.children {
                        let clean = clean_name(&child.name);
                        write!(output, "{}{}: ", inner_indent, clean).unwrap();
                        generate_view_init(output, child, depth - 1, model_name, indent_level + 1);
                        writeln!(output, ",").unwrap();
                    }
                }

                write!(output, "{}}}", indent).unwrap();
            }

            write!(
                output,
                "            {}: ",
                clean_name(&hierarchy.name)
            )
            .unwrap();
            generate_view_init(&mut output, hierarchy, view_depth, &model.name, 3);
            writeln!(output, ",").unwrap();
        }

        writeln!(output, "        }}").unwrap();
        writeln!(output, "    }}").unwrap();
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();

        // Main model struct
        writeln!(output, "/// {} simulation model", model.name).unwrap();
        writeln!(output, "pub struct {} {{", model.name).unwrap();
        writeln!(output, "    storage: Vec<u8>,").unwrap();
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();

        writeln!(output, "impl {} {{", model.name).unwrap();
        writeln!(output, "    /// Create a new model instance").unwrap();
        writeln!(output, "    pub fn new() -> Self {{").unwrap();
        writeln!(
            output,
            "        let mut storage = vec![0u8; {}Layout::NUM_STATE_BYTES];",
            model.name
        )
        .unwrap();
        if !model.initial_fn_sym.is_empty() {
            writeln!(output, "        unsafe {{").unwrap();
            writeln!(
                output,
                "            {}_initial(storage.as_mut_ptr() as *mut std::ffi::c_void);",
                model.name
            )
            .unwrap();
            writeln!(output, "        }}").unwrap();
        }
        writeln!(output, "        Self {{ storage }}").unwrap();
        writeln!(output, "    }}").unwrap();
        writeln!(output).unwrap();

        writeln!(output, "    /// Get a view into the model state").unwrap();
        writeln!(output, "    pub fn view(&mut self) -> {}View<'_> {{", model.name).unwrap();
        writeln!(output, "        unsafe {{ {}View::new(&mut self.storage) }}", model.name).unwrap();
        writeln!(output, "    }}").unwrap();
        writeln!(output).unwrap();

        writeln!(output, "    /// Evaluate one simulation step").unwrap();
        writeln!(output, "    pub fn eval(&mut self) {{").unwrap();
        writeln!(output, "        unsafe {{").unwrap();
        writeln!(
            output,
            "            {}_eval(self.storage.as_mut_ptr() as *mut std::ffi::c_void);",
            model.name
        )
        .unwrap();
        writeln!(output, "        }}").unwrap();
        writeln!(output, "    }}").unwrap();
        writeln!(output).unwrap();

        writeln!(output, "    /// Get raw access to the state buffer").unwrap();
        writeln!(output, "    pub fn state(&self) -> &[u8] {{").unwrap();
        writeln!(output, "        &self.storage").unwrap();
        writeln!(output, "    }}").unwrap();
        writeln!(output).unwrap();

        writeln!(output, "    /// Get mutable raw access to the state buffer").unwrap();
        writeln!(output, "    pub fn state_mut(&mut self) -> &mut [u8] {{").unwrap();
        writeln!(output, "        &mut self.storage").unwrap();
        writeln!(output, "    }}").unwrap();
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();

        writeln!(output, "impl Default for {} {{", model.name).unwrap();
        writeln!(output, "    fn default() -> Self {{").unwrap();
        writeln!(output, "        Self::new()").unwrap();
        writeln!(output, "    }}").unwrap();
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();

        // Generate port macros similar to C++ version
        writeln!(output, "/// Macro to iterate over all IO ports").unwrap();
        writeln!(output, "#[macro_export]").unwrap();
        writeln!(output, "macro_rules! {}_ports {{", model.name.to_lowercase()).unwrap();
        writeln!(output, "    ($macro:ident) => {{").unwrap();
        for s in &io {
            writeln!(output, "        $macro!({});", clean_name(&s.name)).unwrap();
        }
        writeln!(output, "    }};").unwrap();
        writeln!(output, "}}").unwrap();
        writeln!(output).unwrap();
    }

    output
}

/// Generate Rust code from a JSON model file and write to output
pub fn generate<P: AsRef<Path>, W: Write>(
    state_json: P,
    output: &mut W,
    view_depth: i32,
) -> io::Result<()> {
    let models = load_models(state_json)?;
    let code = render_rust_code(&models, view_depth);
    output.write_all(code.as_bytes())?;
    Ok(())
}

/// Generate Rust code from a JSON model file and return as string
pub fn generate_to_string<P: AsRef<Path>>(state_json: P, view_depth: i32) -> io::Result<String> {
    let models = load_models(state_json)?;
    Ok(render_rust_code(&models, view_depth))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_clean_name() {
        assert_eq!(clean_name("hello"), "hello");
        assert_eq!(clean_name("hello-world"), "hello_world");
        assert_eq!(clean_name("123abc"), "_123abc");
        assert_eq!(clean_name("fn"), "r#fn");
        assert_eq!(clean_name("match"), "r#match");
    }

    #[test]
    fn test_state_rust_type() {
        let state = StateInfo {
            name: "test".to_string(),
            offset: 0,
            num_bits: 8,
            ty: StateType::Register,
            stride: None,
            depth: None,
        };
        assert_eq!(state_rust_type(&state), "u8");

        let state32 = StateInfo {
            name: "test".to_string(),
            offset: 0,
            num_bits: 32,
            ty: StateType::Register,
            stride: None,
            depth: None,
        };
        assert_eq!(state_rust_type(&state32), "u32");

        let memory = StateInfo {
            name: "mem".to_string(),
            offset: 0,
            num_bits: 32,
            ty: StateType::Memory,
            stride: Some(4),
            depth: Some(256),
        };
        assert_eq!(state_rust_type(&memory), "[u32; 256]");
    }

    #[test]
    fn test_code_generation() {
        let json = r#"[
            {
                "name": "TestModel",
                "numStateBytes": 128,
                "initialFnSym": "",
                "finalFnSym": "",
                "states": [
                    {"name": "clock", "offset": 0, "numBits": 1, "type": "input"},
                    {"name": "reset", "offset": 1, "numBits": 1, "type": "input"},
                    {"name": "data_out", "offset": 2, "numBits": 32, "type": "output"},
                    {"name": "cpu/pc", "offset": 8, "numBits": 32, "type": "register"},
                    {"name": "cpu/regs", "offset": 16, "numBits": 32, "type": "memory", "stride": 4, "depth": 32},
                    {"name": "internal_wire", "offset": 64, "numBits": 8, "type": "wire"}
                ]
            }
        ]"#;

        let raw_models: Vec<RawModelInfo> = serde_json::from_str(json).unwrap();
        assert_eq!(raw_models.len(), 1);
        assert_eq!(raw_models[0].name, "TestModel");
        assert_eq!(raw_models[0].states.len(), 6);

        // Test full model loading with hierarchy grouping
        let temp_file = std::env::temp_dir().join("test_model.json");
        std::fs::write(&temp_file, json).unwrap();

        let models = load_models(&temp_file).unwrap();
        assert_eq!(models.len(), 1);
        assert_eq!(models[0].name, "TestModel");
        assert_eq!(models[0].io.len(), 3); // clock, reset, data_out
        assert_eq!(models[0].hierarchy.len(), 1);

        // Test code generation
        let code = render_rust_code(&models, 2);
        assert!(code.contains("pub struct TestModelLayout;"));
        assert!(code.contains("pub struct TestModel {"));
        assert!(code.contains("fn TestModel_eval"));
        assert!(code.contains("pub clock: &'a mut u8,"));
        assert!(code.contains("pub reset: &'a mut u8,"));
        assert!(code.contains("pub data_out: &'a mut u32,"));

        std::fs::remove_file(&temp_file).ok();
    }

    #[test]
    fn test_hierarchy_grouping() {
        let states = vec![
            StateInfo {
                name: "local_reg".to_string(),
                offset: 0,
                num_bits: 8,
                ty: StateType::Register,
                stride: None,
                depth: None,
            },
            StateInfo {
                name: "cpu/pc".to_string(),
                offset: 4,
                num_bits: 32,
                ty: StateType::Register,
                stride: None,
                depth: None,
            },
            StateInfo {
                name: "cpu/fetch/state".to_string(),
                offset: 8,
                num_bits: 8,
                ty: StateType::Register,
                stride: None,
                depth: None,
            },
            StateInfo {
                name: "mem/data".to_string(),
                offset: 16,
                num_bits: 32,
                ty: StateType::Register,
                stride: None,
                depth: None,
            },
        ];

        let (local, hierarchies) = group_state_by_hierarchy(states);

        // Should have 1 local state
        assert_eq!(local.len(), 1);
        assert_eq!(local[0].name, "local_reg");

        // Should have 2 top-level hierarchies: cpu and mem
        assert_eq!(hierarchies.len(), 2);

        // Find cpu hierarchy
        let cpu = hierarchies.iter().find(|h| h.name == "cpu").unwrap();
        assert_eq!(cpu.states.len(), 1); // pc
        assert_eq!(cpu.children.len(), 1); // fetch

        // Check nested fetch hierarchy
        let fetch = &cpu.children[0];
        assert_eq!(fetch.name, "fetch");
        assert_eq!(fetch.states.len(), 1); // state
    }
}
