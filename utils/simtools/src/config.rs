use serde::Deserialize;

#[derive(Deserialize, Debug, Clone)]
pub struct Cluster {
    #[serde(rename = "coreType")]
    core_type: String,
    isa: String,
    #[serde(rename = "numCores")]
    num_cores: u32,
}

#[derive(Deserialize, Debug, Clone)]
pub struct Io {
    #[serde(rename = "type")]
    ty: String,
    name: String,
    #[serde(rename = "baseAddr")]
    base_addr: String,
}

#[derive(Deserialize, Debug, Clone)]
pub struct Memory {
    #[serde(rename = "type")]
    ty: String,
    #[serde(rename = "baseAddress")]
    base_addr: String,
    length: u64,
}

#[derive(Deserialize, Debug, Clone)]
pub struct Config {
    clusters: Vec<Cluster>,
    io: Vec<Io>,
    memories: Vec<Memory>,
}
