//! P1.G4: coin selection against an independent brute-force reference.

use std::time::Instant;

use offline_core::{
    NoteStatus,
    select::{Selection, select},
    split::{COIN, DENOMINATIONS, FEE_NOTES, zip317_fee},
};
use proptest::prelude::*;

/// Reference: recursive enumeration of every subset, written separately from the Gray-code
/// search. Returns (best exact count, best (change, count) overpay).
fn reference(values: &[u64], amount: u64) -> (Option<usize>, Option<(u64, usize)>) {
    let mut exact: Option<usize> = None;
    let mut over: Option<(u64, usize)> = None;
    fn walk(
        values: &[u64],
        i: usize,
        sum: u64,
        n: usize,
        amount: u64,
        exact: &mut Option<usize>,
        over: &mut Option<(u64, usize)>,
    ) {
        if i == values.len() {
            if n == 0 {
                return;
            }
            if sum == amount + zip317_fee(n, 1) {
                *exact = Some(exact.map_or(n, |e| e.min(n)));
            } else if sum > amount + zip317_fee(n, 2) {
                let c = sum - amount - zip317_fee(n, 2);
                *over = Some(over.map_or((c, n), |o| o.min((c, n))));
            }
            return;
        }
        walk(values, i + 1, sum, n, amount, exact, over);
        walk(values, i + 1, sum + values[i], n + 1, amount, exact, over);
    }
    walk(values, 0, 0, 0, amount, &mut exact, &mut over);
    (exact, over)
}

fn status() -> impl Strategy<Value = NoteStatus> {
    prop_oneof![
        8 => Just(NoteStatus::Available),
        1 => Just(NoteStatus::SpentOffline),
        1 => Just(NoteStatus::Spent),
    ]
}

/// Note values that look like a prepared wallet: denominations, plus odd change notes.
fn value() -> impl Strategy<Value = u64> {
    prop_oneof![
        3 => prop::sample::select(DENOMINATIONS.to_vec()),
        1 => 1_000u64..300_000_000,
    ]
}

fn check(sel: &Selection, candidates: &[(u64, NoteStatus)], amount: u64) -> Result<(), TestCaseError> {
    // T2: never a non-available note.
    prop_assert!(sel.notes.iter().all(|&i| candidates[i].1 == NoteStatus::Available));
    // T3: the selected notes pay the amount plus the fee for this exact shape.
    let total: u64 = sel.notes.iter().map(|&i| candidates[i].0).sum();
    prop_assert_eq!(total, sel.total);
    let outputs = if sel.change == 0 { 1 } else { 2 };
    prop_assert_eq!(sel.fee, zip317_fee(sel.notes.len(), outputs));
    prop_assert_eq!(total, amount + sel.fee + sel.change);
    Ok(())
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(10_000))]

    /// T1 + T2 + T3: matches brute force, never touches spent notes, always covers amount + fee.
    #[test]
    fn matches_brute_force(
        notes in prop::collection::vec((value(), status()), 0..=12),
        amount in 1_000u64..400_000_000,
    ) {
        let available: Vec<u64> =
            notes.iter().filter(|(_, s)| *s == NoteStatus::Available).map(|(v, _)| *v).collect();
        let (exact, over) = reference(&available, amount);
        match select(&notes, amount) {
            Ok(sel) => {
                check(&sel, &notes, amount)?;
                match exact {
                    Some(n) => {
                        prop_assert!(sel.is_exact(), "exact payment exists but was not chosen");
                        prop_assert_eq!(sel.notes.len(), n);
                    }
                    None => {
                        let (c, n) = over.expect("selection succeeded so an overpay exists");
                        prop_assert_eq!((sel.change, sel.notes.len()), (c, n));
                    }
                }
            }
            Err(_) => prop_assert!(exact.is_none() && over.is_none(), "refused but the reference found a set"),
        }
    }
}

fn prepared_wallet() -> Vec<(u64, NoteStatus)> {
    DENOMINATIONS
        .iter()
        .flat_map(|d| [*d, *d])
        .map(|v| (v, NoteStatus::Available))
        .collect()
}

#[test]
fn exact_when_amount_plus_fee_is_a_sum_of_denominations() {
    // 0.37 ZEC of notes is 0.2 + 0.1 + 0.05 + 0.02 (4 spends, fee 20,000): the receiver can get
    // exactly 0.37 ZEC minus that fee with no change note.
    let sel = select(&prepared_wallet(), 37 * COIN / 100 - zip317_fee(4, 1)).unwrap();
    assert!(sel.is_exact());
    assert_eq!(sel.notes.len(), 4);
}

/// Documents a design finding: the fee comes out of the sender's notes, so a round amount
/// almost never equals "denominations - fee" and needs a change note.
#[test]
fn round_amounts_need_change_without_fee_sized_notes() {
    for amount in [COIN / 20, 37 * COIN / 100, COIN] {
        let sel = select(&prepared_wallet(), amount).unwrap();
        assert!(!sel.is_exact(), "amount {amount} unexpectedly exact");
    }
}

/// What "Prepare for offline" makes from a large balance: the fee-sized notes plus two each of
/// 0.01 to 0.5 ZEC.
fn prepared_with_fee_notes() -> Vec<(u64, NoteStatus)> {
    FEE_NOTES
        .iter()
        .copied()
        .chain([COIN / 100, COIN / 50, COIN / 20, COIN / 10, COIN / 5, COIN / 2].iter().flat_map(|d| [*d, *d]))
        .map(|v| (v, NoteStatus::Available))
        .collect()
}

#[test]
fn round_amounts_are_exact_with_fee_notes() {
    // 0.05 (1 note), 0.37 (4), 0.88 (6), 1.0 (2) ZEC: each pays exactly, the fee note covering
    // the fee, so no change note is made.
    for amount in [COIN / 20, 37 * COIN / 100, 88 * COIN / 100, COIN] {
        let sel = select(&prepared_with_fee_notes(), amount).unwrap();
        assert!(sel.is_exact(), "amount {amount}: change {}", sel.change);
    }
}

#[test]
fn spent_notes_are_never_picked_even_when_they_fit_best() {
    let notes = [
        (COIN / 10 + 10_000, NoteStatus::SpentOffline), // a perfect exact match, but already signed
        (COIN / 10 + 10_000, NoteStatus::Spent),
        (COIN, NoteStatus::Available),
    ];
    let sel = select(&notes, COIN / 10).unwrap();
    assert_eq!(sel.notes, vec![2]);
    assert!(!sel.is_exact());
}

#[test]
fn insufficient_funds_is_an_error() {
    let notes = [(COIN / 100, NoteStatus::Available), (COIN, NoteStatus::SpentOffline)];
    assert!(select(&notes, COIN / 10).is_err());
    assert!(select(&notes, 0).is_err());
    assert!(select(&[], 1).is_err());
}

/// T4: 1,000 selections over 20 notes, p99 under 50 ms (release build).
#[test]
fn selection_is_fast_on_twenty_notes() {
    let notes: Vec<_> = DENOMINATIONS
        .iter()
        .flat_map(|d| [*d, *d])
        .chain([31_234_567, 7_654_321, 123_456, 9_999_999])
        .map(|v| (v, NoteStatus::Available))
        .collect();
    assert_eq!(notes.len(), 20);
    let mut times = Vec::with_capacity(1_000);
    for k in 0..1_000u64 {
        let amount = 1_000 + (k * 7_919_993) % 700_000_000;
        let t = Instant::now();
        let _ = select(&notes, amount);
        times.push(t.elapsed());
    }
    times.sort();
    let p99 = times[989];
    eprintln!("selection over 20 notes: p50 {:?}, p99 {:?}, max {:?}", times[500], p99, times[999]);
    if !cfg!(debug_assertions) {
        assert!(p99.as_millis() < 50, "p99 {p99:?}");
    }
}
