# Shared helpers for Phase 3 (Bluetooth mesh) emulator tests. Source from bash.
source "$(dirname "${BASH_SOURCE[0]}")/../p2/lib.sh"
P3="$ROOT/stress/p3"

bt() {  # bt <serial> on|off
  if [ "$2" = on ]; then "$ADB" -s "$1" shell cmd bluetooth_manager enable >/dev/null
  else "$ADB" -s "$1" shell cmd bluetooth_manager disable >/dev/null; fi
}
bt_is_on() { [ "$("$ADB" -s "$1" shell settings get global bluetooth_on | tr -d '\r')" = 1 ]; }

# offline <serial>: no internet (airplane mode), Bluetooth back on. online <serial>: the reverse
# of airplane mode only; Bluetooth is left as it is.
offline() { airplane "$1" on; sleep 2; bt "$1" on; }
online() { airplane "$1" off; }
has_internet() { "$ADB" -s "$1" shell ping -c 1 -W 2 8.8.8.8 >/dev/null 2>&1; }

# mesh <serial> <python expr over m = state['mesh']>
mesh() { state "$1" "(lambda m: $2)(s['mesh'])"; }
wait_mesh() { wait_state "$1" "(lambda m: $2)(s['mesh'])" "${3:-120}"; }
node_id() { mesh "$1" "m['node']"; }
queue_has() { [ "$(mesh "$1" "any(e['txid'] == '$2' for e in m['queue'])")" = True ]; }
# entry <serial> <txid> <field>
entry() { mesh "$1" "next((e['$3'] for e in m['queue'] if e['txid'] == '$2'), None)"; }

# inject <serial> <raw-hex-file> [hops] [txid]: as if a peer pushed it (verified first).
# Prints the result JSON.
inject() {
  local s=$1 f=$2 hops=${3:-1} txid=${4:-}
  "$ADB" -s "$s" shell run-as "$PKG" rm -f files/inject_result.json files/in_tx.tmp
  push_app_file "$s" in_tx.tmp "$f"
  local want; want=$(wc -c <"$f" | tr -d ' ')
  local i; for i in $(seq 1 20); do
    [ "$("$ADB" -s "$s" shell run-as "$PKG" stat -c %s files/in_tx.tmp 2>/dev/null | tr -d '\r')" = "$want" ] && break; sleep 0.5
  done
  "$ADB" -s "$s" shell run-as "$PKG" mv files/in_tx.tmp files/in_tx.hex
  if [ -n "$txid" ]; then hook "$s" RELAY_INJECT --es hops "$hops" --es txid "$txid"; else hook "$s" RELAY_INJECT --es hops "$hops"; fi
  for i in $(seq 1 300); do
    app_file "$s" inject_result.json 2>/dev/null | grep -q '"status"' && { app_file "$s" inject_result.json; echo; return 0; }
    sleep 1
  done
  echo '{"status":"timeout"}'
}

# received_raw <serial> <n>: raw hex of the n-th payment this phone received (from files/received)
received_raw() {
  local f; f=$("$ADB" -s "$1" shell run-as "$PKG" ls files/received | tr -d '\r' | grep '\.json$' | sed -n "${2}p")
  app_file "$1" "received/$f" | python3 -c "import json,sys; print(json.load(sys.stdin)['raw_hex'])"
}

mesh_log() { "$ADB" -s "$1" logcat -d -s 'MESH:*'; }
# log_has <serial> <ERE>: a MESH log line matches. Reads the whole log (grep -c, never grep -q:
# under pipefail an early-exiting grep makes the pipeline fail).
log_has() { [ "$(mesh_log "$1" | grep -cE "$2" || true)" -gt 0 ]; }
# log_line <serial> <ERE>: last matching MESH log line, or nothing
log_line() { mesh_log "$1" | grep -E "$2" | tail -1 || true; }
# net_off / net_on <serial>: internet off/on with Wi-Fi and mobile data, leaving Bluetooth alone
# (airplane mode would cycle the Bluetooth radio, and emulator Bluetooth degrades after many
# radio cycles). Airplane mode is turned off first so it is not what keeps a phone offline.
net_off() { airplane "$1" off; "$ADB" -s "$1" shell svc wifi disable; "$ADB" -s "$1" shell svc data disable; }
net_on() { airplane "$1" off; "$ADB" -s "$1" shell svc wifi enable; "$ADB" -s "$1" shell svc data enable; }
no_default_net() { "$ADB" -s "$1" shell dumpsys connectivity | grep -c "Active default network: none" | grep -qx 1; }
net_is_off() { ! has_internet "$1" && no_default_net "$1"; }
