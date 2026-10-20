# Shared settings for the Phase 0 Zcash spikes. Source this file.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DT="$ROOT/third_party/zcash-devtool/target/release/zcash-devtool"
W="${WALLET_DIR:-$ROOT/wallets/testnet-a}"   # WALLET_DIR: run against a copy
ID="$ROOT/wallets/testnet-a.age"
RUNS="$ROOT/spikes/runs"

# One account per spike so offline-built txs never pick the same note.
ACCT_A=95defbbe-3c89-4d63-b057-a7961943a69b   # funding, change, online expiry-0 test
ACCT_B=52a70598-2d18-421f-b412-b1f273cb2ac2   # P0.G3.T2: broadcast after >= 4 h
ACCT_C=cbc406ec-6c58-4b1c-a589-36a026e61b36   # P0.G3.T3: broadcast after >= 24 h
ACCT_D=ab16e8ea-92db-46e5-bcfa-01bb17db1ede   # P0.G3.T5: deliberate double spend

ADDR_A=utest1gn9lcnlpfjx3tu8g7gu86r0fmqksc94ufsf52eqy2h7udkeh3700q5azwg6ekvq5p937s22nxzp5d6jvsgqscjjvryathda0yn2r8sa8xkkyxz73ydx8c64wsrang0a2ukheugg2m8u4yf93w2rw72as23v48stdmrtuu03zkdllmjj6vh3sm598pjagjkl2l0h7nfula9qvcd70frg
ADDR_B=utest1s50tledvraupnmmkyunryczqvmjrlm6r583czlhzrl8s7sskr3pgzkzupqts2qehzm0y2zjc0lp8kqdex0nefmgg5u4spr9tle673ns3sa0vpp7ym234jp0f9lrj8xrjumgmtmt6n9a6al26azxpr9kqpuu6jw2mg3j7m5vj54qplg734ty5nee66gw8w7v4f7rqp2vxqd7pz0k2axw
ADDR_C=utest1m6uankyfexlx96ljn0200cvujrt36pwutk2epr7tr76ztxqf4e20d7rw3m2xh5envrd484wyeaa9j9gecn5t6fc8772jg7uq8d3har5t39lt87kljzp9uwqp5f25v4yhku0w8uenwlfs5whqxjr389mfw3370dulkg37ew3k399namajertejtyjtnrjddaw08t0ehzmmd3vs7td62m
ADDR_D=utest1kkh4094vrhm0vu4t0vr0y5hrawlu870qt9fp6pgvc6d205e5gxvs54authhnh9qu9xj5awnpd7c36nyae04xn439e6xy99aeyts2x89cequd2n927lv6r65rwlwfl3yc8mj7u3wdrze22snd0cj8aamt2ltt33waktxe4x7pdedyumfq7mg0k6dwhgpmy3ve6vsqqvrae8v02tu6zeg

# Run a command with all networking denied by the macOS sandbox.
nonet() { sandbox-exec -p '(version 1)(allow default)(deny network*)' "$@"; }

# Current chain tip from the lightwalletd server.
# Retries: lightwalletd drops the odd request, and one failure must not abort a `set -e` script.
tip() {
  local t i
  for i in 1 2 3 4 5 6; do
    t=$("$DT" wallet -w "$W" get-info 2>/dev/null | python3 -c 'import sys, json; print(json.load(sys.stdin)["chain_tip_height"])' 2>/dev/null) \
      && [ -n "$t" ] && { echo "$t"; return 0; }
    sleep 10
  done
  return 1
}

# Sync the wallet, retrying transient lightwalletd failures.
sync_wallet() {
  local i
  for i in 1 2 3 4 5; do
    "$DT" wallet -w "$W" sync >/dev/null 2>&1 && return 0
    sleep 15
  done
  return 1
}
