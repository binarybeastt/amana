//! P1.G3.T1 / T2: split plans balance exactly and stay within limits.

use offline_core::split::{DENOMINATIONS, FEE_NOTES, MAX_OUTPUTS, PER_DENOMINATION, plan_split, zip317_fee};
use proptest::prelude::*;

proptest! {
    #![proptest_config(ProptestConfig::with_cases(10_000))]

    /// 10,000 random balances from 0.001 to 100 ZEC, held in 1 to 20 notes.
    #[test]
    fn plans_balance_exactly(total in 100_000u64..=10_000_000_000, notes in 1usize..=20) {
        let plan = plan_split(total, notes).unwrap();
        // Outputs + fee = input, to the zatoshi.
        prop_assert_eq!(plan.output_total() + plan.fee, total);
        // At most 20 outputs, every output positive.
        prop_assert!(plan.outputs() <= MAX_OUTPUTS);
        prop_assert!(plan.outputs() >= 1);
        prop_assert!(plan.denominations.iter().all(|d| *d > 0));
        // The fee is exactly ZIP 317 for this transaction's shape.
        prop_assert_eq!(plan.fee, zip317_fee(notes, plan.outputs()));
        // Only known denominations, at most two of each, largest first.
        for d in &plan.denominations {
            prop_assert!(DENOMINATIONS.contains(d));
            prop_assert!(plan.denominations.iter().filter(|x| *x == d).count() <= PER_DENOMINATION);
        }
        prop_assert!(plan.denominations.windows(2).all(|w| w[0] >= w[1]));
        // Fee-sized notes: known sizes, at most one of each, smallest first.
        prop_assert!(plan.fee_notes.iter().all(|f| FEE_NOTES.contains(f)));
        prop_assert!(plan.fee_notes.windows(2).all(|w| w[0] < w[1]));
    }
}

#[test]
fn balances_that_cannot_pay_the_fee_are_refused() {
    for notes in 1..=20 {
        let fee = zip317_fee(notes, 1);
        for total in [0, 1, fee - 1, fee] {
            assert!(plan_split(total, notes).is_err(), "total={total} notes={notes}");
        }
    }
}
