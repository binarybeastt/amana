#!/usr/bin/env bash
# Offline cash is ready 3 confirmations after the split is mined, and "ready" means payable.
# On <phone>: PREPARE (split everything outside the pocket), wait for it to be mined, then watch
# every block: the split's pocket notes must have state.json `ready` false before 3
# confirmations and true from 3 on. At 3 confirmations, PAY <zats> to <payee>: it must succeed and
# (pick zats so no older pocket notes can pay it exactly) spend a note of the fresh split, whose
# outputs are paid to our own external address (the library's "untrusted" case). Throughout,
# state.json's ready notes must be exactly the available pocket Ironwood notes 3 deep at the
# wallet's synced tip, and balance_split.available their sum.
#   ./ready_at_3conf.sh <phone> <payee> [zats=10000]
set -uo pipefail
source "$(dirname "$0")/../p3/lib.sh"
S=$1; P=$2; ZATS=${3:-10000}
LOG="$ROOT/stress/p5/logs/ready_at_3conf.log"; mkdir -p "$(dirname "$LOG")"; : >"$LOG"
say() { echo "$(date -u +%T) $*" | tee -a "$LOG"; }
fail() { say "FAIL: $*"; exit 1; }

# Python check of state.json's ready flags against the rule, at the scanned tip.
CHECK="(lambda t, ns: (sorted(n['note'] for n in ns if n['ready']) == sorted(n['note'] for n in ns
  if n['status'] == 'available' and n['pool'] == 'ironwood' and n['mined_height'] is not None
  and n['mined_height'] + 3 <= t + 1)) and s['balance_split']['available'] == sum(n['value'] for n in ns if n['ready']))(
  s['sync']['scanned'], s['prepare'].get('notes', []))"

for s in "$S" "$P"; do launch "$s"; net_on "$s"; bt "$s" on; done
wait_synced "$S" 900 || fail "$S not synced"
wait_state "$S" "s['prepare']['state'] in ('idle', 'ready') and s['prepare']['wallet'] > 0" 120 \
  || fail "$S has nothing outside the pocket to prepare: $(state "$S" "s['prepare']")"
PAYEE=$(state "$P" "s['address']")
say "before: tip $(state "$S" "s['sync']['scanned']") wallet $(state "$S" "s['prepare']['wallet']") ready $(state "$S" "sum(1 for n in s['prepare']['notes'] if n['ready'])")/$(state "$S" "len(s['prepare']['notes'])") rule ok $(state "$S" "$CHECK")"

hook "$S" PREPARE
wait_state "$S" "s['prepare']['state'] == 'waiting'" 180 || fail "split not sent: $(state "$S" "s.get('prepare')")"
SPLIT=$(state "$S" "s['prepare']['txid']"); say "split $SPLIT sent"
wait_state "$S" "s['prepare']['state'] != 'waiting' and any(n['note'].startswith('$SPLIT') and n['mined_height'] for n in s['prepare']['notes'])" 1800 \
  || fail "split not mined"
MINED=$(state "$S" "next(n['mined_height'] for n in s['prepare']['notes'] if n['note'].startswith('$SPLIT'))")
say "split mined at $MINED: $(state "$S" "len([n for n in s['prepare']['notes'] if n['note'].startswith('$SPLIT')])") pocket notes"

# Block by block until 3 confirmations (scanned >= MINED + 2).
last=-1; bad=0
while :; do
  t=$(state "$S" "s['sync']['scanned']")
  if [ "$t" != "$last" ]; then
    r=$(state "$S" "sorted({n['ready'] for n in s['prepare']['notes'] if n['note'].startswith('$SPLIT')})")
    ok=$(state "$S" "$CHECK")
    want=$( [ $((t - MINED + 1)) -ge 3 ] && echo "[True]" || echo "[False]" )
    say "tip $t: $((t - MINED + 1)) confirmations, split notes ready $r (want $want), rule ok $ok"
    [ "$r" = "$want" ] && [ "$ok" = True ] || bad=1
    last=$t
    [ $((t - MINED + 1)) -ge 3 ] && break
  fi
  sleep 5
done
[ $bad = 0 ] || fail "ready flags wrong before or at 3 confirmations"

prev=$(state "$S" "s['pay'].get('txid') or ''")
hook "$S" CLEAR_PAY; sleep 3
hook "$S" PAY --es to "$PAYEE" --es zats "$ZATS"
wait_state "$S" "s['pay']['state'] in ('ready', 'failed') and s['pay'].get('txid') != '$prev'" 600 || fail "PAY never finished"
[ "$(state "$S" "s['pay']['state']")" = ready ] || fail "PAY failed at 3 confirmations: $(state "$S" "s['pay']['error']")"
t=$(state "$S" "s['sync']['scanned']")
say "paid $ZATS at tip $t ($((t - MINED + 1)) confirmations): $(state "$S" "s['pay']['txid']") spent $(state "$S" "s['pay']['spent_notes']")"
[ "$(state "$S" "any(k.startswith('$SPLIT') for k in s['pay']['spent_notes'])")" = True ] || fail "payment did not spend a fresh split note"
sleep 5
[ "$(state "$S" "$CHECK")" = True ] || fail "ready flags disagree with the rule after paying"
say "after: ready $(state "$S" "sum(1 for n in s['prepare']['notes'] if n['ready'])") notes, balance_split $(state "$S" "s['balance_split']")"
say PASS
