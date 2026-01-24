#[repr(C)]
pub struct Signal {
    pub name: *const std::os::raw::c_char,
    pub offset: u32,
    pub num_bits: u32,
    pub ty: SignalType,
    // for memories:
    pub stride: u32,
    pub depth: u32,
}

#[repr(C)]
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum SignalType {
    Input,
    Output,
    Register,
    Memory,
    Wire,
}

#[repr(C)]
pub struct Hierarchy {
    pub name: *const std::os::raw::c_char,
    pub num_states: u32,
    pub num_children: u32,
    pub states: *mut Signal,
    pub children: *mut Hierarchy,
}

#[repr(C)]
pub struct StaticHierarchy {
    pub name: *const std::os::raw::c_char,
    pub num_states: u32,
    pub num_children: u32,
    pub states: &'static [Signal],
    pub children: &'static [StaticHierarchy],
}

// SAFETY: StaticHierarchy contains only raw pointers to static strings
unsafe impl Sync for StaticHierarchy {}
