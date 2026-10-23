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

proptest! {
    #![proptest_config(ProptestConfig::with_cases(10_000))]

    /// Partial prepare: the pocket notes never exceed the target, the change takes the rest, the
    /// plan still balances exactly, and it fits in one transaction.
    #[test]
    fn partial_plans_stay_under_target(total in 1_000_000u64..=10_000_000_000, notes in 1usize..=20, pct in 1u64..=99) {
        let target = total * pct / 100;
        match offline_core::split::plan_target(target, total, notes) {
            Ok(Some(plan)) => {
                let pocket = plan.output_total() - plan.remainder;
                prop_assert!(pocket <= target);
                // Exact, to within dust, even below the smallest denomination.
                prop_assert!(pocket + 5_000 >= target);
                prop_assert!(pocket > 0);
                prop_assert!(plan.remainder > 0);
                prop_assert_eq!(plan.output_total() + plan.fee, total);
                prop_assert!(plan.outputs() <= MAX_OUTPUTS);
                prop_assert_eq!(plan.fee, zip317_fee(notes, plan.outputs()));
            }
            // Nearly everything: the whole balance is split instead.
            Ok(None) => prop_assert!(target + zip317_fee(notes, MAX_OUTPUTS) + 5_000 >= total),
            // Below the smallest note size.
            Err(_) => prop_assert!(target < FEE_NOTES[0]),
        }
    }
}

#[test]
fn partial_split_tags_every_payment_with_the_pocket_memo() {
    use offline_core::split::{plan_target, zip321_uri};
    let plan = plan_target(25_000_000, 100_000_000, 1).unwrap().unwrap();
    let uri = zip321_uri("utest1abc", &plan.payments(), Some(b"offline-cash"));
    // base64url("offline-cash") = "b2ZmbGluZS1jYXNo"
    assert_eq!(uri.matches("b2ZmbGluZS1jYXNo").count(), plan.payments().len());
    assert!(uri.starts_with("zcash:?address=utest1abc&amount="));
    assert!(uri.contains("&memo=b2ZmbGluZS1jYXNo"));
    assert!(uri.contains("&memo.1=b2ZmbGluZS1jYXNo"));
    // No memo: the URI is as before.
    assert!(!zip321_uri("utest1abc", &[1_000_000], None).contains("memo"));
}
