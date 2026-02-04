use serde::Deserialize;

#[derive(Deserialize, Debug, Clone)]
#[allow(dead_code)]
pub struct Cluster {
    #[serde(rename = "coreType")]
    core_type: String,
    isa: String,
    #[serde(rename = "numCores")]
    num_cores: u32,
}

#[derive(Deserialize, Debug, Clone)]
#[allow(dead_code)]
pub struct Io {
    #[serde(rename = "type")]
    ty: String,
    name: String,
    #[serde(rename = "baseAddr")]
    base_addr: String,
}

#[derive(Deserialize, Debug, Clone)]
#[allow(dead_code)]
pub struct Memory {
    #[serde(rename = "type")]
    ty: String,
    #[serde(rename = "baseAddress")]
    base_addr: String,
    length: u64,
}

#[derive(Deserialize, Debug, Clone)]
#[allow(dead_code)]
pub struct Config {
    clusters: Vec<Cluster>,
    io: Vec<Io>,
    memories: Vec<Memory>,
}

impl Config {
    pub fn isa(&self) -> Option<&str> {
        self.clusters.first().map(|cluster| cluster.isa.as_str())
    }

    pub fn xlen(&self) -> u8 {
        match self.isa() {
            Some(isa) if isa.contains("rv64") => 64,
            Some(_) => 32,
            None => 32,
        }
    }

    pub fn num_uarts(&self) -> usize {
        self.io.iter().filter(|io| io.ty == "uart").count()
    }
}
