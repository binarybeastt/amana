//! JNI entry points for the Android app (`dev.offlinezec.wallet.core.NativeCore`).
//!
//! Every function returns JSON (or a string) and never lets a Rust error or panic cross into
//! the JVM as a crash: errors and panics become a `NativeCoreException` with the message.
//! The app calls these only from its separate `:core` process, so this crate's bundled SQLite
//! never shares a process with the SDK's (two SQLite copies in one process cannot see each
//! other's file locks).

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;

use anyhow::{Context, anyhow};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, jstring};
use serde::Serialize;

use crate::WalletStore;
use crate::wallet::parse_network;

const EXCEPTION_CLASS: &str = "dev/offlinezec/wallet/core/NativeCoreException";

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn panic_message(p: &(dyn std::any::Any + Send)) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        (*s).to_owned()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_owned()
    }
}

/// Runs `f`, returning its string as a Java string; on error or panic throws
/// `NativeCoreException` and returns null.
fn run<F>(env: &mut JNIEnv, f: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> anyhow::Result<String>,
{
    let outcome = catch_unwind(AssertUnwindSafe(|| f(env)));
    let message = match outcome {
        Ok(Ok(s)) => match env.new_string(s) {
            Ok(js) => return js.into_raw(),
            Err(e) => format!("returning result: {e}"),
        },
        Ok(Err(e)) => format!("{e:#}"),
        Err(p) => format!("panic in offline-core: {}", panic_message(&*p)),
    };
    // If an exception is already pending (from a JNI call), keep it.
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new(EXCEPTION_CLASS, message);
    }
    std::ptr::null_mut()
}

fn string(env: &mut JNIEnv, s: &JString) -> anyhow::Result<String> {
    Ok(env.get_string(s).context("reading string argument")?.into())
}

fn opt_string(env: &mut JNIEnv, s: &JString) -> anyhow::Result<Option<String>> {
    if s.is_null() { Ok(None) } else { string(env, s).map(Some) }
}

fn bytes(env: &mut JNIEnv, b: &JByteArray) -> anyhow::Result<Vec<u8>> {
    env.convert_byte_array(b).context("reading byte array argument")
}

fn open(env: &mut JNIEnv, db: &JString, network: &JString) -> anyhow::Result<WalletStore> {
    let path = string(env, db)?;
    let network = parse_network(&string(env, network)?)?;
    WalletStore::open_file(Path::new(&path), network)
}

fn json<T: Serialize>(v: &T) -> anyhow::Result<String> {
    Ok(serde_json::to_string(v)?)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_version(mut env: JNIEnv, _: JClass) -> jstring {
    run(&mut env, |_| {
        Ok(format!("offline-core {} (zcash_client_sqlite 0.23.0-pre.1, as the ZODL Android SDK 5.2.1)", env!("CARGO_PKG_VERSION")))
    })
}

/// Test hook for P2.G1.T3: `kind` 0 returns a Rust error, 1 panics.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_testFailure(
    mut env: JNIEnv,
    _: JClass,
    kind: jint,
) -> jstring {
    run(&mut env, |_| match kind {
        0 => Err(anyhow!("forced Rust error (test hook)")),
        _ => panic!("forced Rust panic (test hook)"),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_status(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
) -> jstring {
    run(&mut env, |env| json(&open(env, &db, &network)?.status()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_notes(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
) -> jstring {
    run(&mut env, |env| json(&open(env, &db, &network)?.notes()?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_planSplit(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
    account: JString,
    address: JString,
) -> jstring {
    run(&mut env, |env| {
        let store = open(env, &db, &network)?;
        let (account, address) = (string(env, &account)?, string(env, &address)?);
        json(&store.plan_split_request(&account, &address)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_inFlightOnlineTxs(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
) -> jstring {
    run(&mut env, |env| json(&open(env, &db, &network)?.in_flight_online_txs()?))
}

#[derive(Serialize)]
struct PaymentJson<'a> {
    #[serde(flatten)]
    built: &'a crate::pay::BuiltPayment,
    raw_hex: String,
}

/// Builds, proves, signs and stores a payment (write-before-send); returns it with its raw
/// bytes as hex. `memo` may be null.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_payOffline(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
    seed: JByteArray,
    account: JString,
    to: JString,
    amount: jlong,
    memo: JString,
) -> jstring {
    run(&mut env, |env| {
        let mut store = open(env, &db, &network)?;
        let seed = secrecy::SecretVec::new(bytes(env, &seed)?);
        let (account, to, memo) = (string(env, &account)?, string(env, &to)?, opt_string(env, &memo)?);
        let amount = u64::try_from(amount).context("negative amount")?;
        use secrecy::ExposeSecret;
        let built = store.pay_offline(seed.expose_secret(), &account, &to, amount, memo.as_deref())?;
        json(&PaymentJson { raw_hex: hex(&built.raw), built: &built })
    })
}

#[derive(Serialize)]
struct PendingJson {
    txid: String,
    expiry_height: u32,
    raw_hex: String,
}

/// Stored payments not mined yet (to broadcast once online).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_pendingPayments(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
) -> jstring {
    run(&mut env, |env| {
        let store = open(env, &db, &network)?;
        let out: Vec<PendingJson> = store
            .pending_payments()?
            .into_iter()
            .map(|(txid, expiry_height, raw)| PendingJson { txid, expiry_height, raw_hex: hex(&raw) })
            .collect();
        json(&out)
    })
}

/// Verifies a payment received offline. `account` may be null (any account of this wallet).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_verifyPayment(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
    raw: JByteArray,
    account: JString,
) -> jstring {
    run(&mut env, |env| {
        let mut store = open(env, &db, &network)?;
        let (raw, account) = (bytes(env, &raw)?, opt_string(env, &account)?);
        let received = store.verify_payment(&raw, account.as_deref())?;
        let verified = received.is_verified();
        let mut v = serde_json::to_value(&received)?;
        v["verified"] = serde_json::Value::Bool(verified);
        Ok(v.to_string())
    })
}

/// `{"mined_height": n}` or `{"mined_height": null}` for a txid (display order).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_minedHeight(
    mut env: JNIEnv,
    _: JClass,
    db: JString,
    network: JString,
    txid: JString,
) -> jstring {
    run(&mut env, |env| {
        let store = open(env, &db, &network)?;
        let txid = string(env, &txid)?;
        Ok(serde_json::json!({ "mined_height": store.mined_height(&txid)? }).to_string())
    })
}

/// Builds the proof verifying keys ahead of the first receive.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_offlinezec_wallet_core_NativeCore_prewarm(mut env: JNIEnv, _: JClass) -> jstring {
    run(&mut env, |_| {
        let t = std::time::Instant::now();
        crate::receive::prewarm_verifier();
        Ok(format!("{} ms", t.elapsed().as_millis()))
    })
}
