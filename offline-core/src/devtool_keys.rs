//! Desktop only: read the seed of a zcash-devtool wallet (`keys.toml`, mnemonic encrypted with an
//! age identity). The Android app passes its seed from the SDK instead.

use std::{io::Read, path::Path};

use anyhow::{Context, anyhow};
use bip0039::{English, Mnemonic};
use secrecy::{ExposeSecret, SecretVec};

pub fn seed_from_devtool_wallet(wallet_dir: &Path, identity_file: &Path) -> anyhow::Result<SecretVec<u8>> {
    let keys = std::fs::read_to_string(wallet_dir.join("keys.toml")).context("reading keys.toml")?;
    let table: toml::Table = keys.parse().context("parsing keys.toml")?;
    let ciphertext = table
        .get("mnemonic")
        .and_then(|v| v.as_str())
        .ok_or_else(|| anyhow!("keys.toml has no mnemonic"))?;

    let identities = age::IdentityFile::from_file(identity_file.to_string_lossy().into_owned())?
        .into_identities()?;
    let decryptor = age::Decryptor::new(age::armor::ArmoredReader::new(ciphertext.as_bytes()))?;
    let mut buf = Vec::new();
    let read = decryptor
        .decrypt(identities.iter().map(|i| i.as_ref() as _))?
        .read_to_end(&mut buf);
    let phrase = SecretVec::new(buf);
    read?;

    let text = std::str::from_utf8(phrase.expose_secret())?;
    let seed = <Mnemonic<English>>::from_phrase(text)?.to_seed("");
    Ok(SecretVec::new(seed.to_vec()))
}
