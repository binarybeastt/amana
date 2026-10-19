//! Coin selection for offline payments: pay exactly where possible, otherwise overpay as little
//! as possible (the overpay comes back as a change note).
//!
//! The fee depends on the choice: ZIP 317 charges per logical action, `max(spends, outputs)`,
//! and an exact payment has one output where an overpay has two (payment + change). So every
//! candidate set is judged with its own fee.

use anyhow::bail;
use serde::Serialize;

use crate::{NoteInfo, NoteStatus, split::zip317_fee};

/// Exhaustive search is used up to this many candidate notes (2^24 subsets, tens of ms).
/// "Prepare for offline" makes 20 notes (7 fee-sized), so this covers the normal case with room
/// for a few leftover notes.
pub const EXHAUSTIVE_LIMIT: usize = 24;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Selection {
    /// Indices into the candidate list, ascending.
    pub notes: Vec<usize>,
    pub total: u64,
    pub fee: u64,
    /// Value returned to the sender as a change note (0 for an exact payment).
    pub change: u64,
}

impl Selection {
    pub fn is_exact(&self) -> bool {
        self.change == 0
    }
}

/// Picks notes for a payment of `amount` zatoshis. Only `available` notes are ever considered.
///
/// Preference: an exact payment with the fewest notes; failing that, the smallest change, then
/// the fewest notes.
pub fn select(candidates: &[(u64, NoteStatus)], amount: u64) -> anyhow::Result<Selection> {
    if amount == 0 {
        bail!("amount must be positive");
    }
    let usable: Vec<(usize, u64)> = candidates
        .iter()
        .enumerate()
        .filter(|(_, (v, s))| *s == NoteStatus::Available && *v > 0)
        .map(|(i, (v, _))| (i, *v))
        .collect();

    let best = if usable.len() <= EXHAUSTIVE_LIMIT {
        exhaustive(&usable, amount)
    } else {
        greedy(&usable, amount)
    };
    match best {
        Some(sel) => Ok(sel),
        None => {
            let have: u64 = usable.iter().map(|(_, v)| v).sum();
            bail!("insufficient available notes: have {have} zats, need {amount} plus fee")
        }
    }
}

/// Convenience wrapper over the wallet's note list.
pub fn select_notes(notes: &[NoteInfo], amount: u64) -> anyhow::Result<Selection> {
    let candidates: Vec<_> = notes.iter().map(|n| (n.value, n.status)).collect();
    select(&candidates, amount)
}

/// Scores a set: `Some((change, count))` if it can pay, with change 0 meaning exact.
fn judge(sum: u64, count: usize, amount: u64) -> Option<(u64, u64)> {
    let exact_fee = zip317_fee(count, 1);
    if sum == amount + exact_fee {
        return Some((0, exact_fee));
    }
    let fee = zip317_fee(count, 2);
    (sum > amount + fee).then(|| (sum - amount - fee, fee))
}

fn exhaustive(usable: &[(usize, u64)], amount: u64) -> Option<Selection> {
    let n = usable.len();
    // Walk all subsets in Gray-code order so each step adds or removes exactly one note.
    let mut mask: u32 = 0;
    let mut sum: u64 = 0;
    let mut count = 0usize;
    let mut best: Option<(u64, usize, u32, u64)> = None; // (change, count, mask, fee)
    for i in 1u32..(1u32 << n) {
        let bit = i.trailing_zeros() as usize;
        mask ^= 1 << bit;
        if mask & (1 << bit) != 0 {
            sum += usable[bit].1;
            count += 1;
        } else {
            sum -= usable[bit].1;
            count -= 1;
        }
        if let Some((change, fee)) = judge(sum, count, amount) {
            let better = match best {
                None => true,
                Some((bc, bn, _, _)) => (change, count) < (bc, bn),
            };
            if better {
                best = Some((change, count, mask, fee));
            }
        }
    }
    best.map(|(change, _, mask, fee)| {
        let notes: Vec<usize> = (0..n).filter(|b| mask & (1 << b) != 0).map(|b| usable[b].0).collect();
        let total = notes.iter().map(|&i| usable.iter().find(|(j, _)| *j == i).unwrap().1).sum();
        Selection { notes, total, fee, change }
    })
}

/// Fallback for very large note sets: largest notes first until the payment is covered.
fn greedy(usable: &[(usize, u64)], amount: u64) -> Option<Selection> {
    let mut sorted = usable.to_vec();
    sorted.sort_by_key(|a| std::cmp::Reverse(a.1));
    let mut picked = Vec::new();
    let mut sum = 0;
    for (i, v) in sorted {
        picked.push(i);
        sum += v;
        if let Some((change, fee)) = judge(sum, picked.len(), amount) {
            picked.sort_unstable();
            return Some(Selection { notes: picked, total: sum, fee, change });
        }
    }
    None
}
