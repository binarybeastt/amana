//! "Prepare for offline": plan a self-send that breaks a balance into cash-like denominations.
//!
//! Offline, a payment can only use whole notes, so the wallet first splits its balance into a
//! spread of note sizes, the way cash works: two each of 0.01 ZEC upward.
//!
//! The fee comes out of the sender's notes too, so a round amount is only payable exactly if
//! some notes add up to amount + fee. Fee-sized notes make that possible: a payment from j
//! denomination notes plus one fee note has j + 1 spends and one output, so its ZIP 317 fee is
//! 5,000 × max(2, j + 1) zatoshis, which is exactly one of [`FEE_NOTES`] for j up to 7.

use anyhow::bail;
use serde::Serialize;

pub const COIN: u64 = 100_000_000;

/// Denominations, largest first, in zatoshis.
pub const DENOMINATIONS: [u64; 8] = [
    2 * COIN,
    COIN,
    COIN / 2,
    COIN / 5,
    COIN / 10,
    COIN / 20,
    COIN / 50,
    COIN / 100,
];

/// Fee-sized notes: 5,000 × k zatoshis for k = 2..=8 (0.00175 ZEC in all), one of each.
pub const FEE_NOTES: [u64; 7] = [10_000, 15_000, 20_000, 25_000, 30_000, 35_000, 40_000];

/// How many notes of each denomination the split aims for.
pub const PER_DENOMINATION: usize = 2;

/// Upper bound on outputs in one split: larger transactions get slow to prove and to relay.
pub const MAX_OUTPUTS: usize = 20;

/// ZIP 317 marginal fee per logical action.
pub const MARGINAL_FEE: u64 = 5_000;
/// ZIP 317 grace actions: every transaction pays for at least this many.
pub const GRACE_ACTIONS: usize = 2;

/// ZIP 317 fee for an Orchard-style (Orchard or Ironwood) transaction, whose logical actions are
/// `max(spends, outputs)`.
pub fn zip317_fee(spends: usize, outputs: usize) -> u64 {
    MARGINAL_FEE * spends.max(outputs).max(GRACE_ACTIONS) as u64
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SplitPlan {
    /// Fee-sized outputs, smallest first.
    pub fee_notes: Vec<u64>,
    /// Denomination outputs, largest first.
    pub denominations: Vec<u64>,
    /// Whatever is left after the denominations and the fee, as one more note (0 = none).
    pub remainder: u64,
    pub fee: u64,
}

impl SplitPlan {
    pub fn outputs(&self) -> usize {
        self.fee_notes.len() + self.denominations.len() + usize::from(self.remainder > 0)
    }

    pub fn output_total(&self) -> u64 {
        self.fee_notes.iter().sum::<u64>() + self.denominations.iter().sum::<u64>() + self.remainder
    }

    /// Every output amount except the remainder (which the wallet makes as change).
    pub fn payments(&self) -> Vec<u64> {
        self.denominations.iter().chain(&self.fee_notes).copied().collect()
    }
}

/// Fills up to `slots` outputs, keeping one slot for the remainder unless a note uses up exactly
/// what is left. Priority: fee notes (smallest first), then up to [`PER_DENOMINATION`] of each
/// denomination smallest first so everyday amounts are covered even from a small balance, then
/// whatever is left into the largest denominations that fit.
fn fill(spendable: u64, slots: usize) -> (Vec<u64>, Vec<u64>, u64) {
    struct Fill {
        left: u64,
        used: usize,
        slots: usize,
    }
    impl Fill {
        fn take(&mut self, value: u64, count: &mut usize, max: usize) {
            while *count < max
                && value <= self.left
                && (self.used + 1 < self.slots || value == self.left)
            {
                *count += 1;
                self.used += 1;
                self.left -= value;
            }
        }
    }
    let mut f = Fill { left: spendable, used: 0, slots };
    let mut fee_counts = [0usize; FEE_NOTES.len()];
    for (i, v) in FEE_NOTES.iter().enumerate() {
        f.take(*v, &mut fee_counts[i], 1);
    }
    let mut counts = [0usize; DENOMINATIONS.len()];
    for i in (0..DENOMINATIONS.len()).rev() {
        f.take(DENOMINATIONS[i], &mut counts[i], PER_DENOMINATION);
    }
    for i in 0..DENOMINATIONS.len() {
        f.take(DENOMINATIONS[i], &mut counts[i], PER_DENOMINATION);
    }
    let fee_notes = FEE_NOTES.iter().zip(fee_counts).filter(|(_, n)| *n > 0).map(|(v, _)| *v).collect();
    let denominations = DENOMINATIONS
        .iter()
        .zip(counts)
        .flat_map(|(d, n)| std::iter::repeat_n(*d, n))
        .collect();
    (fee_notes, denominations, f.left)
}

/// Plans a split of `input_total` zatoshis held in `input_notes` notes, so that
/// `outputs + fee == input_total` exactly, with at most [`MAX_OUTPUTS`] outputs.
pub fn plan_split(input_total: u64, input_notes: usize) -> anyhow::Result<SplitPlan> {
    if input_notes == 0 {
        bail!("nothing to split: no input notes");
    }
    // The fee depends on the output count and the output count on what the fee leaves, so
    // shrink the assumed output count until the plan's own count gives the same fee.
    let mut slots = MAX_OUTPUTS;
    loop {
        let fee = zip317_fee(input_notes, slots);
        if input_total <= fee {
            bail!("balance {input_total} zats cannot cover the {fee} zat fee");
        }
        let (fee_notes, denominations, remainder) = fill(input_total - fee, slots);
        let plan = SplitPlan { fee_notes, denominations, remainder, fee };
        let outputs = plan.outputs();
        if zip317_fee(input_notes, outputs) == fee {
            return Ok(plan);
        }
        slots = outputs;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fee_matches_zip317_examples() {
        assert_eq!(zip317_fee(1, 1), 10_000);
        assert_eq!(zip317_fee(1, 2), 10_000);
        assert_eq!(zip317_fee(1, 3), 15_000);
        assert_eq!(zip317_fee(4, 1), 20_000);
        assert_eq!(zip317_fee(1, 20), 100_000);
    }

    #[test]
    fn large_balance_gets_fee_notes_and_two_of_each_up_to_half_a_zec() {
        let plan = plan_split(10 * COIN, 1).unwrap();
        assert_eq!(plan.fee_notes, FEE_NOTES.to_vec());
        for d in [COIN / 100, COIN / 50, COIN / 20, COIN / 10, COIN / 5, COIN / 2] {
            assert_eq!(plan.denominations.iter().filter(|x| **x == d).count(), 2, "{d}");
        }
        assert_eq!(plan.outputs(), MAX_OUTPUTS);
        assert_eq!(plan.output_total() + plan.fee, 10 * COIN);
    }

    #[test]
    fn fee_notes_cover_payments_of_one_to_seven_denomination_notes() {
        for j in 1..=7 {
            assert!(FEE_NOTES.contains(&zip317_fee(j + 1, 1)), "{j} notes");
        }
    }

    #[test]
    fn small_balance_uses_what_fits() {
        let plan = plan_split(COIN / 10 + 30_000, 1).unwrap();
        assert_eq!(plan.output_total() + plan.fee, COIN / 10 + 30_000);
        assert!(plan.denominations.iter().all(|d| DENOMINATIONS.contains(d)));
    }

    #[test]
    fn small_balance_covers_small_denominations_first() {
        // 0.5395 ZEC in 6 notes: two each of 0.01, 0.02, 0.05 and 0.1 before anything larger.
        let plan = plan_split(53_950_000, 6).unwrap();
        for d in [COIN / 100, COIN / 50, COIN / 20, COIN / 10] {
            assert_eq!(plan.denominations.iter().filter(|x| **x == d).count(), 2, "{d}");
        }
        assert_eq!(plan.output_total() + plan.fee, 53_950_000);
    }

    #[test]
    fn too_small_to_pay_the_fee() {
        assert!(plan_split(10_000, 1).is_err());
        assert!(plan_split(9_999, 1).is_err());
        assert!(plan_split(COIN, 0).is_err());
    }
}
