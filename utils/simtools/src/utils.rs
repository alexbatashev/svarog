use anyhow::Context;
use std::path::Path;
use xshell::{Shell, cmd};

pub fn clone_repo(url: &str, dest: &Path) -> anyhow::Result<()> {
    if dest.exists() {
        std::fs::remove_dir_all(dest)?;
    }

    let sh = Shell::new().unwrap();

    cmd!(sh, "git clone --depth 1 {url} {dest}")
        .run()
        .context(format!("Failed to clone {url}"))?;
    Ok(())
}
