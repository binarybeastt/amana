//! P1.G2.T1: random sequences of note events never produce an illegal transition.

use offline_core::{NoteEvent, NoteStatus};
use proptest::prelude::*;

fn event() -> impl Strategy<Value = NoteEvent> {
    prop_oneof![
        Just(NoteEvent::Confirmed),
        Just(NoteEvent::SignedOffline),
        Just(NoteEvent::SeenMined),
    ]
}

fn start() -> impl Strategy<Value = NoteStatus> {
    prop_oneof![Just(NoteStatus::Pending), Just(NoteStatus::Available)]
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(10_000))]

    #[test]
    fn transitions_stay_legal(initial in start(), events in prop::collection::vec(event(), 0..50)) {
        let mut status = initial;
        let mut signed = 0;
        let mut mined = false;
        let mut left_available = false;
        for ev in &events {
            let before = status;
            match status.apply(*ev) {
                Ok(next) => {
                    // Once a note leaves available it never comes back.
                    if before == NoteStatus::Available && next != NoteStatus::Available {
                        left_available = true;
                    }
                    prop_assert!(!(left_available && next == NoteStatus::Available));
                    // Nothing ever becomes pending.
                    prop_assert_ne!(next, NoteStatus::Pending);
                    if *ev == NoteEvent::Confirmed {
                        prop_assert_eq!(before, NoteStatus::Pending);
                    }
                    if *ev == NoteEvent::SeenMined {
                        prop_assert_ne!(before, NoteStatus::Pending);
                    }
                    if *ev == NoteEvent::SignedOffline {
                        // Signing is only accepted from available, and only once.
                        prop_assert_eq!(before, NoteStatus::Available);
                        signed += 1;
                        prop_assert_eq!(signed, 1);
                    }
                    if *ev == NoteEvent::SeenMined {
                        mined = true;
                    }
                    status = next;
                }
                Err(e) => {
                    // Rejections leave the note untouched.
                    prop_assert_eq!(e.from, before);
                    // Signing is refused for every status except available.
                    if *ev == NoteEvent::SignedOffline {
                        prop_assert_ne!(before, NoteStatus::Available);
                    }
                }
            }
        }
        let expected = if mined {
            NoteStatus::Spent
        } else if signed == 1 {
            NoteStatus::SpentOffline
        } else if initial == NoteStatus::Pending && !events.contains(&NoteEvent::Confirmed) {
            NoteStatus::Pending
        } else {
            NoteStatus::Available
        };
        prop_assert_eq!(status, expected);
    }
}

#[test]
fn every_pair_is_covered() {
    use NoteEvent::*;
    use NoteStatus::*;
    assert_eq!(Pending.apply(Confirmed), Ok(Available));
    assert!(Pending.apply(SignedOffline).is_err());
    assert!(Pending.apply(SeenMined).is_err());
    assert!(Available.apply(Confirmed).is_err());
    assert!(SpentOffline.apply(Confirmed).is_err());
    assert!(Spent.apply(Confirmed).is_err());
    assert_eq!(Available.apply(SignedOffline), Ok(SpentOffline));
    assert_eq!(Available.apply(SeenMined), Ok(Spent));
    assert_eq!(SpentOffline.apply(SeenMined), Ok(Spent));
    assert_eq!(Spent.apply(SeenMined), Ok(Spent));
    assert!(SpentOffline.apply(SignedOffline).is_err());
    assert!(Spent.apply(SignedOffline).is_err());
    // Dust: never signable, stays dust when confirmed, can still be seen spent.
    assert_eq!(Dust.apply(Confirmed), Ok(Dust));
    assert!(Dust.apply(SignedOffline).is_err());
    assert_eq!(Dust.apply(SeenMined), Ok(Spent));
}
