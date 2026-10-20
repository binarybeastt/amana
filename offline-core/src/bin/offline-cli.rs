//! Desktop CLI over `offline-core`, used to prove each piece before it goes into the app.

use std::path::PathBuf;

use clap::{Parser, Subcommand};
use offline_core::{
    WalletStore,
    devtool_keys::seed_from_devtool_wallet,
    split::zec,
    wallet::parse_network,
};
use secrecy::ExposeSecret;

#[derive(Parser)]
#[command(version, about = "Offline shielded Zcash payments: desktop tool")]
struct Cli {
    /// Wallet directory containing data.sqlite (e.g. a zcash-devtool wallet).
    #[arg(short, long)]
    wallet_dir: PathBuf,

    /// Network: "test" or "main".
    #[arg(short, long, default_value = "test")]
    network: String,

    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Show the synced chain tip and each account's balances.
    Status {
        /// Print JSON (zatoshis) instead of a table.
        #[arg(long)]
        json: bool,
    },
    /// List shielded notes with their offline status (available, spent-offline, spent).
    Notes {
        /// Print JSON instead of a table.
        #[arg(long)]
        json: bool,
    },
    /// Plan a "prepare for offline" split of an account's available Ironwood notes and print it
    /// as a ZIP 321 payment request to the given address (normally the account's own).
    PlanSplit {
        /// Account UUID whose notes to split.
        #[arg(long)]
        account: String,
        /// Address to pay the denomination and fee-sized notes to.
        #[arg(long)]
        address: String,
        /// Print JSON instead of text.
        #[arg(long)]
        json: bool,
    },
    /// Build, prove, sign and store a payment with no network; writes the raw bytes for the receiver.
    Pay {
        /// Account UUID to pay from.
        #[arg(long)]
        account: String,
        /// Recipient address.
        #[arg(long)]
        to: String,
        /// Amount in zatoshis.
        #[arg(long)]
        amount: u64,
        #[arg(long)]
        memo: Option<String>,
        /// age identity file that decrypts the wallet's mnemonic (zcash-devtool wallets).
        #[arg(short, long)]
        identity: PathBuf,
        /// Where to write the raw transaction bytes.
        #[arg(long)]
        raw_out: Option<PathBuf>,
    },
    /// List stored payments that are not mined yet; optionally export their raw bytes.
    Pending {
        /// Directory to write `<txid>.raw` files into.
        #[arg(long)]
        raw_dir: Option<PathBuf>,
    },
    /// Verify a payment received as raw bytes, offline: proofs, signatures, anchor, what it pays us.
    Receive {
        raw: PathBuf,
        /// Receiving account UUID (default: any account of this wallet).
        #[arg(long)]
        account: Option<String>,
    },
    /// Release note-selection locks left by an interrupted `pay` (also done at the start of `pay`).
    ReleaseLocks,
    /// Read expiry, actions and fee from raw transaction bytes.
    InspectRaw { raw: PathBuf },
    /// Write-before-send: verify a signed PCZT and store it so its notes become spent-offline.
    /// TEST ONLY: build and sign a payment like `pay` but do NOT store it; writes the PCZT.
    /// Its notes stay available, so the PCZT must never be broadcast. For the store crash test.
    BuildPczt {
        #[arg(long)]
        account: String,
        #[arg(long)]
        to: String,
        #[arg(long)]
        amount: u64,
        #[arg(short, long)]
        identity: PathBuf,
        /// Where to write the signed PCZT.
        #[arg(long)]
        out: PathBuf,
    },
    StorePczt {
        /// Path to the combined (proven + signed) PCZT.
        pczt: PathBuf,
        /// Where to write the raw transaction bytes for the receiver.
        #[arg(long)]
        raw_out: Option<PathBuf>,
    },
}

fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    let network = parse_network(&cli.network)?;
    let mut store = WalletStore::open(&cli.wallet_dir, network)?;

    match cli.command {
        Command::Status { json } => {
            let status = store.status()?;
            if json {
                println!("{}", serde_json::to_string_pretty(&status)?);
            } else {
                match status.chain_tip_height {
                    Some(h) => println!("Synced to height {h}"),
                    None => println!("Never synced"),
                }
                for a in &status.accounts {
                    println!(
                        "{}  {:<18} total {:>14}  ironwood {:>14}  orchard {:>14}",
                        a.account_uuid,
                        a.name.as_deref().unwrap_or("-"),
                        zec(a.total),
                        zec(a.ironwood_spendable),
                        zec(a.orchard_spendable),
                    );
                }
            }
        }
        Command::Notes { json } => {
            let notes = store.notes()?;
            if json {
                println!("{}", serde_json::to_string_pretty(&notes)?);
            } else {
                for n in &notes {
                    println!(
                        "{} {:<8} {:>14} {:<13} {}:{}{}",
                        &n.account_uuid[..8],
                        n.pool,
                        zec(n.value),
                        format!("{:?}", n.status),
                        &n.txid[..16],
                        n.output_index,
                        n.spent_by.as_deref().map(|t| format!("  spent by {}", &t[..16])).unwrap_or_default(),
                    );
                }
            }
        }
        Command::PlanSplit { account, address, json } => {
            let req = store.plan_split_request(&account, &address)?;
            let (plan, uri) = (&req.plan, &req.zip321);
            if json {
                println!("{}", serde_json::to_string_pretty(&req)?);
            } else {
                println!("inputs: {} notes, {} ZEC", req.input_notes, zec(req.input_total));
                for d in &plan.denominations {
                    println!("  {}", zec(*d));
                }
                for f in &plan.fee_notes {
                    println!("  {} (fee note)", zec(*f));
                }
                println!("  remainder {}  fee {}", zec(plan.remainder), zec(plan.fee));
                println!("{uri}");
            }
        }
        Command::Pay { account, to, amount, memo, identity, raw_out } => {
            let seed = seed_from_devtool_wallet(&cli.wallet_dir, &identity)?;
            let built = store.pay_offline(seed.expose_secret(), &account, &to, amount, memo.as_deref())?;
            if let Some(path) = raw_out {
                std::fs::write(&path, &built.raw)?;
            }
            println!("{}", serde_json::to_string_pretty(&built)?);
        }
        Command::Pending { raw_dir } => {
            for (txid, expiry, raw) in store.pending_payments()? {
                println!("{txid}  expiry {expiry}  {} bytes", raw.len());
                if let Some(dir) = &raw_dir {
                    std::fs::create_dir_all(dir)?;
                    std::fs::write(dir.join(format!("{txid}.raw")), &raw)?;
                }
            }
        }
        Command::Receive { raw, account } => {
            let bytes = std::fs::read(&raw)?;
            match store.verify_payment(&bytes, account.as_deref()) {
                Ok(p) => {
                    let verdict = if p.is_verified() {
                        "received, not yet settled"
                    } else if !p.is_for_us() {
                        "not for this wallet"
                    } else {
                        "valid, but anchor unknown: not verified"
                    };
                    println!("{verdict}");
                    println!("{}", serde_json::to_string_pretty(&p)?);
                }
                Err(e) => {
                    println!("rejected: {e:#}");
                    std::process::exit(2);
                }
            }
        }
        Command::ReleaseLocks => {
            println!("released {} selection locks", store.release_selection_locks()?);
        }
        Command::InspectRaw { raw } => {
            let info = offline_core::pay::inspect_raw(&std::fs::read(&raw)?)?;
            println!("{}", serde_json::to_string_pretty(&info)?);
        }
        Command::BuildPczt { account, to, amount, identity, out } => {
            let seed = seed_from_devtool_wallet(&cli.wallet_dir, &identity)?;
            let signed = store.build_signed_pczt(seed.expose_secret(), &account, &to, amount, None)?;
            std::fs::write(&out, &signed.pczt)?;
            println!("signed PCZT (not stored): {} bytes, fee {}", signed.pczt.len(), signed.fee);
        }
        Command::StorePczt { pczt, raw_out } => {
            let bytes = std::fs::read(&pczt)?;
            let (txid, raw) = store.store_signed(&bytes)?;
            if let Some(path) = raw_out {
                std::fs::write(&path, &raw)?;
            }
            println!("stored {txid} ({} bytes)", raw.len());
        }
    }
    Ok(())
}
