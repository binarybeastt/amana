//! Desktop CLI over `offline-core`, used to prove each piece before it goes into the app.

use std::path::PathBuf;

use clap::{Parser, Subcommand};
use offline_core::{NoteStatus, WalletStore, split::plan_split, wallet::parse_network};

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
    /// Write-before-send: verify a signed PCZT and store it so its notes become spent-offline.
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
            let notes: Vec<_> = store
                .notes()?
                .into_iter()
                .filter(|n| {
                    n.account_uuid == account && n.pool == "ironwood" && n.status == NoteStatus::Available
                })
                .collect();
            let total: u64 = notes.iter().map(|n| n.value).sum();
            let plan = plan_split(total, notes.len())?;
            let uri = zip321_uri(&address, &plan.payments());
            if json {
                let out = serde_json::json!({
                    "input_notes": notes.len(), "input_total": total, "plan": plan, "zip321": uri,
                });
                println!("{}", serde_json::to_string_pretty(&out)?);
            } else {
                println!("inputs: {} notes, {} ZEC", notes.len(), zec(total));
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

/// ZIP 321 request paying each amount to `address`. The remainder is left for the wallet's change.
fn zip321_uri(address: &str, amounts: &[u64]) -> String {
    let parts: Vec<String> = amounts
        .iter()
        .enumerate()
        .map(|(i, z)| {
            let amount = zec(*z).trim_end_matches('0').trim_end_matches('.').to_string();
            if i == 0 {
                format!("address={address}&amount={amount}")
            } else {
                format!("address.{i}={address}&amount.{i}={amount}")
            }
        })
        .collect();
    format!("zcash:?{}", parts.join("&"))
}

fn zec(zats: u64) -> String {
    format!("{}.{:08}", zats / 100_000_000, zats % 100_000_000)
}
