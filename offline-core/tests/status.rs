use offline_core::{WalletStore, wallet::parse_network};
use rand::{rand_core::UnwrapErr, rngs::SysRng};
use zcash_client_sqlite::{WalletDb, util::SystemClock, wallet::init::init_wallet_db};
use zcash_protocol::consensus::Network;

#[test]
fn parse_network_accepts_only_test_and_main() {
    assert_eq!(parse_network("test").unwrap(), Network::TestNetwork);
    assert_eq!(parse_network("main").unwrap(), Network::MainNetwork);
    assert!(parse_network("regtest").is_err());
    assert!(parse_network("").is_err());
}

#[test]
fn open_refuses_a_missing_wallet_instead_of_creating_one() {
    let dir = tempfile::tempdir().unwrap();
    let err = WalletStore::open(dir.path(), Network::TestNetwork).err().unwrap();
    assert!(err.to_string().contains("no wallet database"), "{err}");
    assert!(!dir.path().join("data.sqlite").exists());
}

#[test]
fn fresh_wallet_has_no_accounts_and_no_tip() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("data.sqlite");
    let mut db = WalletDb::for_path(&path, Network::TestNetwork, SystemClock, UnwrapErr(SysRng)).unwrap();
    init_wallet_db(&mut db, None).unwrap();
    drop(db);

    let status = WalletStore::open(dir.path(), Network::TestNetwork).unwrap().status().unwrap();
    assert_eq!(status.chain_tip_height, None);
    assert!(status.accounts.is_empty());
}
