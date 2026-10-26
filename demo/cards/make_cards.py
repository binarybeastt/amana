"""Renders the demo video's title and info cards (1920x1080 PNG) with headless Chrome."""
import pathlib, subprocess

HERE = pathlib.Path(__file__).parent
FONT = (HERE / "../../android/app/src/main/res/font/inter.ttf").resolve()
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

CSS = f"""
@font-face {{ font-family: Inter; src: url('file://{FONT}'); }}
html, body {{ margin: 0; width: 1920px; height: 1080px; background: #0b0b0c; color: #f2f2f2;
  font-family: Inter, 'Helvetica Neue', sans-serif; }}
.wrap {{ box-sizing: border-box; height: 100%; padding: 140px 170px; display: flex;
  flex-direction: column; justify-content: center; }}
h1 {{ font-size: 96px; font-weight: 700; letter-spacing: -2px; margin: 0 0 28px; line-height: 1.05; }}
h2 {{ font-size: 44px; font-weight: 400; color: #a9a9ad; margin: 0; line-height: 1.3; }}
.kicker {{ font-size: 30px; color: #3ddc84; font-weight: 600; letter-spacing: 3px;
  text-transform: uppercase; margin-bottom: 30px; }}
.big {{ font-size: 64px; font-weight: 600; line-height: 1.25; margin: 0; }}
.dim {{ color: #77777c; }}
table {{ font-size: 38px; border-collapse: collapse; margin-top: 10px; }}
td {{ padding: 14px 0; border-bottom: 1px solid #222; }}
td:first-child {{ color: #8d8d92; width: 520px; }}
.hidden {{ color: #3ddc84; font-weight: 600; }}
.mono {{ font-family: Menlo, monospace; font-size: 30px; color: #55555a; word-break: break-all;
  margin-top: 40px; line-height: 1.4; }}
"""

RAW = (HERE / "../raw/tx.raw")
hexdump = RAW.read_bytes()[200:520].hex() if RAW.exists() else ""

CARDS = {
    "title": """<div class=kicker>ZECATHON · Shielded Payments</div>
      <h1>Zcash that works<br>without signal</h1>
      <h2>Private payments, phone to phone. Settle later.</h2>""",
    "hook": """<p class=big>When the power's out and data is down,<br>people still need to pay.</p>
      <p class=big style="margin-top:40px">Cash gets stolen.<br>Mobile money needs a network.</p>""",
    "cast": """<div class=kicker>The demo · Zcash testnet</div>
      <table>
      <tr><td>Ada, bread seller</td><td>a real Android phone</td></tr>
      <tr><td>Tunde, buyer</td><td>emulator</td></tr>
      <tr><td>A stranger's phone</td><td>emulator, relays over Bluetooth</td></tr>
      </table>
      <h2 style="margin-top:50px">The QR is handed to the real phone by a test bridge;<br>the phone verifies the payment itself, offline.</h2>""",
    "onchain": f"""<div class=kicker>What the blockchain sees</div>
      <table>
      <tr><td>Transaction</td><td>5e89ff96…d41710</td></tr>
      <tr><td>Block</td><td>4,544,461 (testnet)</td></tr>
      <tr><td>Shielded actions</td><td>2 (Ironwood)</td></tr>
      <tr><td>Who paid</td><td class=hidden>hidden</td></tr>
      <tr><td>Who was paid</td><td class=hidden>hidden</td></tr>
      <tr><td>How much</td><td class=hidden>hidden</td></tr>
      </table>
      <div class=mono>{hexdump[:420]}…</div>""",
    "limits": """<p class=big>Offline double spending can't be made impossible<br>without special hardware.</p>
      <h2 style="margin-top:40px">So we stop honest mistakes, catch cheats in seconds,<br>and cap what anyone can lose,
      while the strangers<br>carrying your money learn nothing.</h2>""",
    "close": """<div class=kicker>Today ZEC · tomorrow a shielded dollar</div>
      <h1>Pay offline.<br>Privately.</h1>
      <h2>For markets that run on cash and patchy data.</h2>""",
}

for name, body in CARDS.items():
    html = HERE / f"{name}.html"
    html.write_text(f"<!doctype html><meta charset=utf-8><style>{CSS}</style><div class=wrap>{body}</div>")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--window-size=1920,1080", f"--screenshot={HERE / (name + '.png')}",
                    f"file://{html}"], check=True, capture_output=True)
    print(name)
