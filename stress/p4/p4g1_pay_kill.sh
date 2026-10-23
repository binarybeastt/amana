#!/usr/bin/env bash
# P4.G1.T1 + T2, emulator form: kill the app at random points in the pay flow; restart.
#
# Sender S and peer P both offline (Wi-Fi + data off), Bluetooth on, so the only ways a
# payment can leave S are its QR code and the Bluetooth mesh. Run 0 pays with no kill and
# measures how long the pay flow takes (D). Then N runs, each:
#   1. S on the Pay screen; snapshot its notes, stored payments, and P's relay queue.
#   2. PAY hook (same code path as the Pay button); after a random delay, kill:
#      odd runs anywhere in [0, D + 4 s]; even runs in [0.6 D, D + 2 s] (around the store).
#      Odd runs kill both processes (main and :core) like a crash; even runs only the main
#      process, so :core may finish storing a payment the app never heard back about.
#   3. Restart. T1 (p4g1_check.py): every payment that showed signs of leaving the phone
#      (QR shown, returned to the app, pushed over Bluetooth, or new on P) is stored with
#      its notes spent-offline; no note lost or spent by anything but the run's payment.
#   4. T2, for a payment stored this run: listed with raw bytes after restart, those bytes
#      parse to the same txid with expiry 0, the phone pushes it to P over Bluetooth, and
#      its QR can be shown again.
# Finally S goes online alone (P stays offline): every stored payment must be broadcast by S
# and mined.
#   ./p4g1_pay_kill.sh <sender> <peer> [runs=50] [zats=10000] [seed]
set -uo pipefail
source "$(dirname "$0")/../p3/lib.sh"
S=$1; P=$2; N=${3:-50}; ZATS=${4:-10000}; SEED=${5:-$RANDOM}
P4="$ROOT/stress/p4"; OUT="$P4/logs/p4g1"; LOG="$P4/logs/p4g1.log"
CLI="$ROOT/offline-core/target/release/offline-cli"
rm -rf "$OUT"; mkdir -p "$OUT"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }

applog() { "$ADB" -s "$1" logcat -d -s AppController:* MESH:* Payer:*; }
# fresh_ready <serial> <since-ms>: wallet open, state written after since-ms
fresh_ready() { wait_state "$1" "s.get('wallet') == 'ready' and s['updated_at'] > $2" "${3:-240}"; }
pending_json() {
  "$ADB" -s "$1" shell run-as "$PKG" rm -f files/pending.json
  hook "$1" DUMP_PENDING
  local i; for i in $(seq 1 180); do
    app_file "$1" pending.json 2>/dev/null | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null && { app_file "$1" pending.json; return 0; }
    sleep 1
  done
  return 1
}
# notes_json <serial>: offline-core's notes, valid JSON or failure. Waits up to 180 s: a fresh
# :core process first builds its proof verifying keys (over 30 s on a cold emulator).
notes_json() {
  "$ADB" -s "$1" shell run-as "$PKG" rm -f files/core_notes.json
  hook "$1" CORE_STATUS
  local i; for i in $(seq 1 180); do
    app_file "$1" core_notes.json 2>/dev/null | python3 -c 'import json,sys; json.load(sys.stdin)' 2>/dev/null && { app_file "$1" core_notes.json; return 0; }
    sleep 1
  done
  return 1
}
queue_txids() { state "$1" "'\n'.join(e['txid'] for e in s['mesh']['queue'])"; }
# dismiss_anr <serial>: a system "… isn't responding" dialog (e.g. System UI on a loaded
# emulator) covers the app and swallows taps; answer "Wait".
dismiss_anr() {
  "$ADB" -s "$1" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b; b=$("$ADB" -s "$1" exec-out cat /sdcard/ui.xml | python3 -c "
import re, sys
x = sys.stdin.read()
if \"isn't responding\" in x:
    m = re.search(r'text=\"Wait\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"', x)
    if m: a, b, c, d = map(int, m.groups()); print((a + c) // 2, (b + d) // 2)")
  [ -n "$b" ] && { say "dismissed a system 'isn't responding' dialog on $1"; "$ADB" -s "$1" shell input tap $b; sleep 1; }
  return 0
}
pay_screen() { hook "$1" CLEAR_PAY; dismiss_anr "$1"; home "$1"; sleep 1; dismiss_anr "$1"; tap_id "$1" nav_pay; sleep 1; }
main_pid() { "$ADB" -s "$1" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}'; }
core_pid() { "$ADB" -s "$1" shell pidof "$PKG:core" | tr -d '\r'; }
kill_main() { local p; p=$(main_pid "$1"); [ -n "$p" ] && "$ADB" -s "$1" shell run-as "$PKG" kill -9 "$p"; }
# wait_paid <serial> <timeout-s>: until the "paid" log line (pay returned to the app)
wait_paid() {
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    [ "$("$ADB" -s "$1" logcat -d -s AppController:* | grep -c 'paid .* txid' || true)" -gt 0 ] && return 0
    sleep 0.3
  done
  return 1
}
# inspect_raw <hex> <want-txid>: the stored bytes parse to that txid with expiry 0
inspect_raw() {
  local f; f=$(mktemp); python3 -c "import sys; open('$f','wb').write(bytes.fromhex(sys.argv[1]))" "$1"
  "$CLI" -w "$ROOT/wallets/testnet-a" inspect-raw "$f" 2>/dev/null | python3 -c "
import json, sys
i = json.load(sys.stdin); ok = i.get('txid') == '$2' and i.get('expiry_height') == 0
print('ok' if ok else 'bad: ' + json.dumps({k: i.get(k) for k in ('txid', 'expiry_height')}))"
  rm -f "$f"
}

say "P4.G1: $N kill runs, sender $S, peer $P, $ZATS zats per payment, seed $SEED"
# Online first to sync (an earlier, aborted run may have left them offline).
for s in "$S" "$P"; do "$ADB" -s "$s" shell settings put secure stylus_handwriting_enabled 0; net_on "$s"; launch "$s"; done
TO=$(state "$P" "s['address']"); SN=$(node_id "$S")
[ -n "$TO" ] && [ -n "$SN" ] || { say "FAIL: no address/node id"; exit 1; }
wait_synced "$S" 600 || { say "FAIL: sender not synced"; exit 1; }
wait_synced "$P" 600 || { say "FAIL: peer not synced"; exit 1; }
# Every other emulator out of Bluetooth range, so S and P are the only phones that can carry
# a payment (a third phone online would broadcast it and hide whether S itself can).
for o in $("$ADB" devices | awk '/^emulator-/{print $1}'); do
  [ "$o" = "$S" ] || [ "$o" = "$P" ] || { bt "$o" off; say "$o: Bluetooth off (out of range)"; }
done
# Power-cycle both radios once: a netsim controller can come up rejecting the filtered-scan
# command (LE_ADV_FILTER -> UNKNOWN_CONNECTION) and see no adverts until it is cycled.
for s in "$S" "$P"; do net_off "$s"; bt "$s" off; done; sleep 4
for s in "$S" "$P"; do bt "$s" on; done
sleep 8
for s in "$S" "$P"; do net_is_off "$s" || { say "FAIL: $s still online"; exit 1; }; done
PN=$(node_id "$P")
for s in "$S" "$P"; do wait_mesh "$s" "m['ble_running']" 60 || { say "FAIL: $s Bluetooth mesh not running"; exit 1; }; done
wait_mesh "$S" "any(p['node'] == '$PN' for p in m['peers'])" 90 || { say "FAIL: sender does not see the peer over Bluetooth"; exit 1; }
say "both offline (Wi-Fi + data off), Bluetooth on and in range; sender node $SN, peer node $PN"

# ---- Run 0: calibration, no kill.
pay_screen "$S"; "$ADB" -s "$S" logcat -c
t0=$(now_ms); hook "$S" PAY --es to "$TO" --es zats "$ZATS"
wait_paid "$S" 900 || { say "FAIL: calibration pay did not finish"; applog "$S" | tail -20 | tee -a "$LOG"; exit 1; }
D=$(( $(now_ms) - t0 ))
wait_state "$S" "s.get('notes_at_qr', {}).get('txid') == s['pay']['txid']" 60
say "run 0 (no kill): pay flow took ${D} ms, QR shown for $(state "$S" "s['pay']['txid']")"

python3 - "$SEED" "$N" "$D" >"$OUT/delays.txt" <<'EOF'
import random, sys
seed, n, d = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
r = random.Random(seed)
for k in range(1, n + 1):
    lo, hi = (0, d + 4000) if k % 2 else (int(0.6 * d), d + 2000)
    print(r.randint(lo, hi))
EOF

passed=0; stored=0; qr_before=0; declare -a STORED_TXIDS=()
for k in $(seq 1 "$N"); do
  R="$OUT/run$k"; mkdir -p "$R"
  delay=$(sed -n "${k}p" "$OUT/delays.txt")
  mode=$([ $((k % 2)) = 1 ] && echo all || echo main)
  since=$(now_ms); launch "$S"
  fresh_ready "$S" "$since" || { say "run $k: FAIL app not ready"; continue; }
  pay_screen "$S"
  notes_json "$S" >"$R/notes0.json" && pending_json "$S" >"$R/pend0.json" \
    || { say "run $k: FAIL could not snapshot the sender before paying"; continue; }
  queue_txids "$P" >"$R/peer0.txt"
  "$ADB" -s "$S" logcat -c
  t0=$(now_ms); hook "$S" PAY --es to "$TO" --es zats "$ZATS"
  python3 -c "import time; time.sleep(max(0, ($delay - ($(now_ms) - $t0)) / 1000))"
  if [ "$mode" = all ]; then kill9 "$S"; else kill_main "$S"; fi
  at=$(( $(now_ms) - t0 ))
  applog "$S" >"$R/kill.log"
  # Even runs leave :core running; let a payment it was building finish (or not) first.
  if [ "$mode" = main ]; then
    end=$((SECONDS + (D * 2 + 15000) / 1000))
    while [ $SECONDS -lt $end ] && [ -n "$(core_pid "$S")" ] && [ "$(( $(now_ms) - t0 ))" -lt $((D * 2 + 15000)) ]; do sleep 1; done
  fi
  since=$(now_ms); launch "$S"
  fresh_ready "$S" "$since" || { say "run $k: FAIL app not ready after restart"; continue; }
  sleep 7   # two refresh rounds: relay-queue recovery runs there
  notes_json "$S" >"$R/notes1.json" && pending_json "$S" >"$R/pend1.json" \
    || { say "run $k: FAIL could not read the sender after the restart"; continue; }
  queue_txids "$P" >"$R/peer1.txt"
  verdict=$(python3 "$P4/p4g1_check.py" "$R" "$SN")
  echo "$verdict" >"$R/verdict.json"
  ok=$(echo "$verdict" | python3 -c 'import json,sys; print(json.load(sys.stdin)["ok"])')
  tx=$(echo "$verdict" | python3 -c 'import json,sys; print(json.load(sys.stdin)["stored"] or "")')
  qr=$([ "$(grep -c QR_SHOWN "$R/kill.log" || true)" -gt 0 ] && echo yes || echo no)
  t2=""
  if [ -n "$tx" ]; then
    stored=$((stored + 1)); STORED_TXIDS+=("$tx"); [ "$qr" = yes ] && qr_before=$((qr_before + 1))
    raw=$(python3 -c "import json; print(next(p['raw_hex'] for p in json.load(open('$R/pend1.json')) if p['txid'] == '$tx'))")
    t2="raw $(inspect_raw "$raw" "$tx")"
    if wait_state "$P" "any(e['txid'] == '$tx' for e in s['mesh']['queue'])" 180; then t2="$t2, on peer"; else t2="$t2, NOT on peer"; ok=False; fi
    pay_screen "$S"; hook "$S" SHOW_PENDING --es txid "$tx"
    if wait_state "$S" "s.get('notes_at_qr', {}).get('txid') == '$tx'" 60; then t2="$t2, QR again"; else t2="$t2, NO QR"; ok=False; fi
    hook "$S" CLEAR_PAY
    case "$t2" in *"raw ok"*) ;; *) ok=False ;; esac
  fi
  if [ "$ok" = True ]; then passed=$((passed + 1)); r=pass; else r=FAIL; fi
  say "run $k: $r · kill $mode at ${at} ms (planned $delay) · $( [ -n "$tx" ] && echo "stored ${tx:0:16}… (QR before kill: $qr; $t2)" || echo "nothing stored") · avail $(echo "$verdict" | python3 -c 'import json,sys; v=json.load(sys.stdin); print(v["available_before"], "->", v["available_after"])')$( [ "$ok" = True ] || echo " · $verdict")"
done
say "kill runs: $passed/$N pass; $stored stored before or during the kill ($qr_before with the QR already shown)"

# ---- T2 on the network: S alone comes online and must broadcast every stored payment.
ALL=(); while read -r t; do [ -n "$t" ] && ALL+=("$t"); done < <(pending_json "$S" | python3 -c 'import json,sys; print("\n".join(p["txid"] for p in json.load(sys.stdin) if p["expiry_height"] == 0))')
say "online: sender has ${#ALL[@]} stored payments (this test: ${#STORED_TXIDS[@]} + run 0); peer stays offline"
net_on "$S"; t0=$(now_ms)
left=${#ALL[@]}; end=$((SECONDS + 2400))
while [ $SECONDS -lt $end ]; do
  left=$(pending_json "$S" | python3 -c 'import json,sys; print(sum(1 for p in json.load(sys.stdin) if p["expiry_height"] == 0))')
  [ "$left" = 0 ] && break
  sleep 20
done
say "after $(( ($(now_ms) - t0) / 1000 )) s online: $left of ${#ALL[@]} still unmined"
for t in "${ALL[@]}"; do say "  ${t:0:16}… mined at $(entry "$S" "$t" mined_height); sender's last send: $(entry "$S" "$t" last_result)"; done
# Nobody else could have sent them: P offline the whole time, every other phone out of range.
say "peer still offline: $(net_is_off "$P" && echo yes || echo NO); peer sent: $(mesh "$P" "m['stats']")"
net_on "$P"
if [ "$passed" = "$N" ] && [ "$left" = 0 ] && [ "$stored" -gt 0 ]; then say "P4.G1: PASS"; exit 0; fi
say "P4.G1: FAIL"; exit 1
