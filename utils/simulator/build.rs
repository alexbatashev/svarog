use std::fs::{self, File};
use std::io::{BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::env;

use anyhow::{anyhow, Context};
use flate2::read::GzDecoder;
use glob::glob;
use sha2::{Digest, Sha256};
use tar::Archive;
use xshell::{cmd, Shell};

const CIRCT_VERSION: &str = "firtool-1.139.0";

struct PlatformRelease {
    url: &'static str,
    sha256: &'static str,
    is_zip: bool,
}

fn main() -> anyhow::Result<()> {
    let manifest_dir = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let workspace_root = manifest_dir
        .parent()
        .and_then(|p| p.parent())
        .ok_or_else(|| anyhow!("Failed to find workspace root"))?;

    let tools_dir = workspace_root.join("target").join("arcilator");

    download_tools(&tools_dir)?;

    println!("cargo:rerun-if-changed=../../configs/");

    for entry in glob("../../configs/*.yaml").expect("Failed to read glob pattern") {
        match entry {
            Ok(path) => {
                build_simulator(&path, &tools_dir)?;
            }
            Err(e) => {
                return Err(anyhow!("{:?}", e));
            }
        }
    }

    Ok(())
}

fn build_simulator(path: &PathBuf, tools_dir: &Path) -> anyhow::Result<()> {
    let model_name = path
        .file_stem()
        .ok_or(anyhow!("Failed to get filename"))?;
    let model_name = model_name.to_str().unwrap();
    let model_name_sane = model_name.replace("-", "_");

    let out_dir = env::var("OUT_DIR").unwrap();

    fs::create_dir_all(format!("{out_dir}/{model_name}"))?;

    let sh = Shell::new()?;
    let bin_dir = tools_dir.join("bin");

    sh.change_dir("../../");
    cmd!(sh, "./mill -i svarog.runMain svarog.VerilogGenerator --target-dir={out_dir}/{model_name} --config=configs/{model_name}.yaml --format=firrtl").run()?;
    cmd!(sh,
        "{bin_dir}/firtool --ir-hw --format=mlir {out_dir}/{model_name}/SvarogSoC.firrtl -o {out_dir}/{model_name}.hw.mlir"
    ).run()?;
    cmd!(sh,
        "{bin_dir}/arcilator --observe-wires --observe-ports --observe-named-values --observe-registers --observe-memories --async-resets-as-sync --emit-llvm -o {out_dir}/{model_name}.ll {out_dir}/{model_name}.hw.mlir"
    ).run()?;
    cmd!(sh, "{bin_dir}/llc -O3 -o {out_dir}/{model_name}.o {out_dir}/{model_name}.ll").run()?;
    cmd!(sh, "{bin_dir}/llvm-objcopy --filetype=obj --redefine-sym=SvarogSoC_eval={model_name_sane}_eval {out_dir}/{model_name}.o").run()?;

    Ok(())
}

fn get_platform_release() -> anyhow::Result<PlatformRelease> {
    #[cfg(all(target_os = "linux", target_arch = "x86_64"))]
    {
        Ok(PlatformRelease {
            url: "https://github.com/llvm/circt/releases/download/firtool-1.139.0/circt-full-static-linux-x64.tar.gz",
            sha256: "3d6370161e1f0bd78391d07446efa0d3abd16ea4b691471e4b64374045c9b045",
            is_zip: false,
        })
    }

    #[cfg(all(target_os = "macos", target_arch = "x86_64"))]
    {
        Ok(PlatformRelease {
            url: "https://github.com/llvm/circt/releases/download/firtool-1.139.0/circt-full-static-macos-x64.tar.gz",
            sha256: "eb8636a819d192833fb9f93cb9103aa6a85e1e810ec040e925bc7909a83f1758",
            is_zip: false,
        })
    }

    #[cfg(all(target_os = "macos", target_arch = "aarch64"))]
    {
        // Use x64 binary on ARM macOS (runs via Rosetta 2)
        Ok(PlatformRelease {
            url: "https://github.com/llvm/circt/releases/download/firtool-1.139.0/circt-full-static-macos-x64.tar.gz",
            sha256: "eb8636a819d192833fb9f93cb9103aa6a85e1e810ec040e925bc7909a83f1758",
            is_zip: false,
        })
    }

    #[cfg(all(target_os = "windows", target_arch = "x86_64"))]
    {
        Ok(PlatformRelease {
            url: "https://github.com/llvm/circt/releases/download/firtool-1.139.0/circt-full-static-windows-x64.zip",
            sha256: "9226caa3599333cb38bbb357294d7c8775a1fe6712e2c03313a406ac8e8e4d83",
            is_zip: true,
        })
    }

    #[cfg(not(any(
        all(target_os = "linux", target_arch = "x86_64"),
        all(target_os = "macos", target_arch = "x86_64"),
        all(target_os = "macos", target_arch = "aarch64"),
        all(target_os = "windows", target_arch = "x86_64")
    )))]
    {
        Err(anyhow!(
            "Unsupported platform: {} {}",
            std::env::consts::OS,
            std::env::consts::ARCH
        ))
    }
}

fn verify_sha256(path: &Path, expected_hash: &str) -> anyhow::Result<bool> {
    let file = File::open(path).context("Failed to open file for hash verification")?;
    let mut reader = BufReader::new(file);
    let mut hasher = Sha256::new();

    let mut buffer = [0u8; 8192];
    loop {
        let bytes_read = reader.read(&mut buffer)?;
        if bytes_read == 0 {
            break;
        }
        hasher.update(&buffer[..bytes_read]);
    }

    let hash = hasher.finalize();
    let hash_hex = format!("{:x}", hash);

    Ok(hash_hex == expected_hash)
}

fn download_file(url: &str, dest: &Path) -> anyhow::Result<()> {
    println!("cargo:warning=Downloading CIRCT tools from {}", url);

    let response = ureq::get(url)
        .call()
        .context("Failed to download CIRCT release")?;

    let mut dest_file = File::create(dest).context("Failed to create destination file")?;

    let mut reader = response.into_reader();
    let mut buffer = [0u8; 8192];
    let mut total_bytes = 0u64;

    loop {
        let bytes_read = reader.read(&mut buffer)?;
        if bytes_read == 0 {
            break;
        }
        dest_file.write_all(&buffer[..bytes_read])?;
        total_bytes += bytes_read as u64;
    }

    println!(
        "cargo:warning=Downloaded {} bytes to {}",
        total_bytes,
        dest.display()
    );

    Ok(())
}

fn extract_tar_gz(archive_path: &Path, dest_dir: &Path) -> anyhow::Result<()> {
    println!(
        "cargo:warning=Extracting {} to {}",
        archive_path.display(),
        dest_dir.display()
    );

    let file = File::open(archive_path).context("Failed to open archive")?;
    let gz = GzDecoder::new(file);
    let mut archive = Archive::new(gz);

    // Extract to a temporary directory first, then move contents
    let temp_extract_dir = dest_dir.join("_extract_temp");
    fs::create_dir_all(&temp_extract_dir)?;

    archive
        .unpack(&temp_extract_dir)
        .context("Failed to extract tar.gz archive")?;

    // Find the extracted directory (usually named like "firtool-1.139.0")
    // and move its contents to the bin directory
    let bin_dir = dest_dir.join("bin");
    fs::create_dir_all(&bin_dir)?;

    // Look for the bin directory inside the extracted content
    for entry in fs::read_dir(&temp_extract_dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            let inner_bin = path.join("bin");
            if inner_bin.exists() {
                // Move all files from inner bin to our bin directory
                for bin_entry in fs::read_dir(&inner_bin)? {
                    let bin_entry = bin_entry?;
                    let src = bin_entry.path();
                    let dest = bin_dir.join(bin_entry.file_name());
                    fs::rename(&src, &dest).or_else(|_| {
                        // If rename fails (cross-device), copy and delete
                        fs::copy(&src, &dest)?;
                        fs::remove_file(&src)
                    })?;
                }
            }
        }
    }

    // Clean up temp directory
    fs::remove_dir_all(&temp_extract_dir)?;

    println!("cargo:warning=Extraction complete");
    Ok(())
}

#[cfg(target_os = "windows")]
fn extract_zip(archive_path: &Path, dest_dir: &Path) -> anyhow::Result<()> {
    println!(
        "cargo:warning=Extracting {} to {}",
        archive_path.display(),
        dest_dir.display()
    );

    let file = File::open(archive_path).context("Failed to open archive")?;
    let mut archive = zip::ZipArchive::new(file).context("Failed to read zip archive")?;

    let bin_dir = dest_dir.join("bin");
    fs::create_dir_all(&bin_dir)?;

    for i in 0..archive.len() {
        let mut file = archive.by_index(i)?;
        let outpath = match file.enclosed_name() {
            Some(path) => path.to_owned(),
            None => continue,
        };

        // We only care about files in the bin directory
        let path_str = outpath.to_string_lossy();
        if let Some(bin_idx) = path_str.find("/bin/") {
            let filename = &path_str[bin_idx + 5..];
            if !filename.is_empty() && !filename.contains('/') {
                let dest_path = bin_dir.join(filename);
                let mut outfile = File::create(&dest_path)?;
                io::copy(&mut file, &mut outfile)?;

                // Set executable permissions on Unix-like systems
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    fs::set_permissions(&dest_path, fs::Permissions::from_mode(0o755))?;
                }
            }
        }
    }

    println!("cargo:warning=Extraction complete");
    Ok(())
}

#[cfg(not(target_os = "windows"))]
fn extract_zip(_archive_path: &Path, _dest_dir: &Path) -> anyhow::Result<()> {
    Err(anyhow!("ZIP extraction not expected on this platform"))
}

fn download_tools(tools_dir: &Path) -> anyhow::Result<()> {
    let release = get_platform_release()?;

    // Check if tools are already downloaded
    let bin_dir = tools_dir.join("bin");
    let arcilator_path = if cfg!(target_os = "windows") {
        bin_dir.join("arcilator.exe")
    } else {
        bin_dir.join("arcilator")
    };

    // Check for version marker file
    let version_marker = tools_dir.join(".version");
    if arcilator_path.exists() && version_marker.exists() {
        let installed_version = fs::read_to_string(&version_marker).unwrap_or_default();
        if installed_version.trim() == CIRCT_VERSION {
            println!("cargo:warning=CIRCT tools already installed ({})", CIRCT_VERSION);
            return Ok(());
        }
    }

    println!("cargo:warning=CIRCT tools not found or outdated, downloading...");

    // Create tools directory
    fs::create_dir_all(tools_dir).context("Failed to create tools directory")?;

    // Determine archive filename
    let archive_name = if release.is_zip {
        "circt.zip"
    } else {
        "circt.tar.gz"
    };
    let archive_path = tools_dir.join(archive_name);

    // Download the archive
    download_file(release.url, &archive_path)?;

    // Verify hash
    println!("cargo:warning=Verifying SHA256 hash...");
    if !verify_sha256(&archive_path, release.sha256)? {
        fs::remove_file(&archive_path)?;
        return Err(anyhow!(
            "SHA256 hash mismatch! The downloaded file may be corrupted or tampered with."
        ));
    }
    println!("cargo:warning=SHA256 hash verified successfully");

    // Extract the archive
    if release.is_zip {
        extract_zip(&archive_path, tools_dir)?;
    } else {
        extract_tar_gz(&archive_path, tools_dir)?;
    }

    // Clean up archive
    fs::remove_file(&archive_path)?;

    // Write version marker
    fs::write(&version_marker, CIRCT_VERSION)?;

    // Set executable permissions on Unix
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(entries) = fs::read_dir(&bin_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_file() {
                    fs::set_permissions(&path, fs::Permissions::from_mode(0o755))?;
                }
            }
        }
    }

    println!("cargo:warning=CIRCT tools installed successfully");
    Ok(())
}
