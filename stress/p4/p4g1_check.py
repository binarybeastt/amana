#!/usr/bin/env python3
"""P4.G1.T1 check for one kill run. Reads the run's files; prints a JSON verdict.

  p4g1_check.py <run-dir> <sender-node>

Files in <run-dir>:
  notes0.json / notes1.json   sender's notes before the pay / after the restart
  pend0.json / pend1.json     sender's stored, unmined payments before / after
  peer0.txt / peer1.txt       txids in the peer's relay queue before / after
  kill.log                    sender's AppController + MESH log up to the kill

"Bytes sent" = any sign the payment left the sender before the kill: its QR was on screen
(QR_SHOWN), the app had it back from the builder ("paid"), it was pushed over Bluetooth
(FWD), or the peer holds a new tx. Every such txid must be stored (pending) with all of its
notes spent-offline. No note may be lost: each note available before is still available, or
spent-offline by the run's new pending payment.
"""
import json
import re
import sys
from pathlib import Path

d = Path(sys.argv[1])
node = sys.argv[2]
load = lambda n: json.loads((d / n).read_text())
lines = lambda n: [l.strip() for l in (d / n).read_text().splitlines() if l.strip()]

notes0 = {n["note"]: n for n in load("notes0.json")}
notes1 = {n["note"]: n for n in load("notes1.json")}
pend0 = {p["txid"] for p in load("pend0.json")}
pend1 = {p["txid"]: p for p in load("pend1.json")}
peer0, peer1 = set(lines("peer0.txt")), set(lines("peer1.txt"))
log = (d / "kill.log").read_text()

errors = []
new_pend = sorted(set(pend1) - pend0)
if len(new_pend) > 1:
    errors.append(f"more than one new stored payment: {new_pend}")

evidence = {}
for tag, rx in [("qr", r"QR_SHOWN txid=([0-9a-f]{64})"), ("paid", r"paid \d+ zats, txid ([0-9a-f]{64})"),
                ("fwd", r"FWD txid=([0-9a-f]{64})")]:
    for t in re.findall(rx, log):
        evidence.setdefault(t, set()).add(tag)
for t in peer1 - peer0:
    evidence.setdefault(t, set()).add("peer")
# FWD lines for txs stored in earlier runs are recovery traffic, not this run's payment.
sent = {t: sorted(v) for t, v in evidence.items() if t not in pend0}

for t, how in sent.items():
    if t not in pend1:
        errors.append(f"{t[:16]} left the phone ({','.join(how)}) but is not a stored payment")
    spent = [n for n in notes1.values() if n.get("spent_by") == t]
    if not spent:
        errors.append(f"{t[:16]} left the phone ({','.join(how)}) but no note is spent by it")
    for n in spent:
        if n["status"] != "spent-offline":
            errors.append(f"{t[:16]} left the phone but its note {n['note']} is {n['status']}")

for k, n in notes0.items():
    if n["status"] != "available":
        continue
    m = notes1.get(k)
    if m is None:
        errors.append(f"note {k} disappeared")
    elif m["status"] == "available":
        if m.get("spent_by"):
            errors.append(f"note {k} available but spent by {m['spent_by']}")
    elif not (m["status"] == "spent-offline" and m.get("spent_by") in new_pend):
        errors.append(f"note {k} went available -> {m['status']} (spent by {m.get('spent_by')}) with no new stored payment")
for k, m in notes1.items():
    if m["status"] == "spent-offline" and m.get("spent_by") in new_pend and notes0.get(k, {}).get("status") != "available":
        errors.append(f"note {k} spent by the new payment was not available before")
for t in new_pend:
    if not any(n.get("spent_by") == t and n["status"] == "spent-offline" for n in notes1.values()):
        errors.append(f"new stored payment {t[:16]} spends none of our notes")
    if not pend1[t].get("raw_hex"):
        errors.append(f"new stored payment {t[:16]} has no raw bytes")

print(json.dumps({
    "ok": not errors,
    "errors": errors,
    "stored": new_pend[0] if new_pend else None,
    "left_phone": sent,
    "available_before": sum(1 for n in notes0.values() if n["status"] == "available"),
    "available_after": sum(1 for n in notes1.values() if n["status"] == "available"),
}))
