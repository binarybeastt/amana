//! The one "ready to pay offline" rule (`NoteInfo::ready_offline`): `available` alone is not
//! enough. A note is ready only if it is also Ironwood, in the offline pocket, and
//! MIN_CONFIRMATIONS (3) deep for a payment mined at the next block.

use offline_core::{NoteInfo, NoteStatus, notes::MIN_CONFIRMATIONS};

const TIP: u32 = 1_000;

fn pocket(mined: Option<u32>) -> NoteInfo {
    NoteInfo {
        account_uuid: "acct".into(),
        pool: "ironwood",
        txid: "00".repeat(32),
        output_index: 0,
        value: 1_000_000,
        nullifier: mined.map(|_| "11".repeat(32)),
        mined_height: mined,
        has_tree_position: mined.is_some(),
        status: if mined.is_some() { NoteStatus::Available } else { NoteStatus::Pending },
        spent_by: None,
        offline: true,
        ready: false,
    }
}

#[test]
fn pocket_notes_are_ready_three_blocks_after_mining() {
    assert_eq!(MIN_CONFIRMATIONS, 3);
    // Mined at TIP - 2: TIP - 2, TIP - 1 and TIP are three confirmations.
    assert!(pocket(Some(TIP - 2)).ready_offline(TIP + 1));
    assert!(pocket(Some(TIP - 100)).ready_offline(TIP + 1));
    assert!(!pocket(Some(TIP - 1)).ready_offline(TIP + 1), "two confirmations");
    assert!(!pocket(Some(TIP)).ready_offline(TIP + 1), "one confirmation");
    assert!(!pocket(None).ready_offline(TIP + 1), "not mined");
}

#[test]
fn available_is_not_ready_outside_the_pocket_or_ironwood() {
    let old = Some(TIP - 50);
    assert!(!NoteInfo { offline: false, ..pocket(old) }.ready_offline(TIP + 1), "funds from outside");
    assert!(!NoteInfo { pool: "orchard", ..pocket(old) }.ready_offline(TIP + 1), "Orchard");
}

#[test]
fn only_available_notes_are_ready() {
    let old = Some(TIP - 50);
    for status in [NoteStatus::Pending, NoteStatus::SpentOffline, NoteStatus::Spent, NoteStatus::Dust] {
        assert!(!NoteInfo { status, ..pocket(old) }.ready_offline(TIP + 1), "{status:?}");
    }
    assert!(NoteInfo { status: NoteStatus::Available, ..pocket(old) }.ready_offline(TIP + 1));
}

#[test]
fn nothing_is_ready_before_the_first_sync() {
    // notes() passes target 0 when the wallet has no tip.
    assert!(!pocket(Some(0)).ready_offline(0));
}

#[test]
fn ready_is_serialized_for_the_app() {
    let mut n = pocket(Some(TIP - 2));
    n.ready = n.ready_offline(TIP + 1);
    let v = serde_json::to_value(&n).unwrap();
    assert_eq!(v["ready"], true);
    assert_eq!(v["status"], "available");
}
