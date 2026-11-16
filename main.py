"""音声認識でキーワードを聞取り、指定IP:ポートへUDPメッセージを送るアプリ。

Vosk(オフライン音声認識) + sounddevice を使う。
設定は config.json。
"""

import json
import socket
import sys
import time
from pathlib import Path

SAMPLE_RATE = 16000
BLOCK_SIZE = 8000  # 0.5秒分 (16kHz・1ch)


def load_config(path: str = "config.json") -> dict:
    script_dir = Path(__file__).resolve().parent
    with open(script_dir / path, encoding="utf-8") as f:
        return json.load(f)


def send_udp(host: str, port: int, message: str, encoding: str = "utf-8") -> None:
    """UDPデータグラムを1通送信する(送信確認はしない)。"""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.sendto(message.encode(encoding), (host, port))
    print(f"[{time.strftime('%H:%M:%S')}] 送信 -> {host}:{port} {message!r}")


def match_trigger(text: str, triggers: dict) -> str | None:
    """認識テキストに含まれる最初のキーワードに対応メッセージを返す。"""
    lowered = text.lower()
    for word, message in triggers.items():
        if word.lower() in lowered:
            return message
    return None


def main() -> int:
    try:
        from vosk import KaldiRecognizer, Model, SetLogLevel
        import sounddevice as sd
    except ImportError as e:
        print(f"モジュールがありません: {e.name}")
        print("先に `pip install -r requirements.txt` を実行してください。")
        return 1

    cfg = load_config()
    SetLogLevel(-1)

    script_dir = Path(__file__).resolve().parent
    model_path = script_dir / cfg.get("model_path", "model")
    if not model_path.exists():
        print(f"Voskモデルが見つかりません: {model_path}")
        print("https://alphacephei.com/vosk/models からモデル(例: vosk-model-small-ja-0.22.zip)")
        print("をダウンロードし、解凍して ./model に置いてください。")
        return 1

    print(f"モデルを読み込み中... ({model_path})")
    model = Model(str(model_path))
    rec = KaldiRecognizer(model, SAMPLE_RATE)

    triggers = cfg["triggers"]
    host = cfg["target_host"]
    port = int(cfg["target_port"])
    cooldown = float(cfg.get("cooldown_seconds", 2.0))
    encoding = cfg.get("encoding", "utf-8")
    last_sent = 0.0

    print(f"キーワード待受開始: {', '.join(triggers)}")
    print(f"送信先 {host}:{port} / 終了は Ctrl+C")

    def callback(indata, frames, time_info, status):
        nonlocal last_sent
        if status:
            print(f"[audio] {status}", file=sys.stderr)
        if rec.AcceptWaveform(bytes(indata)):
            text = json.loads(rec.Result()).get("text", "").strip()
            if not text:
                return
            print(f"  認識: {text}")
            message = match_trigger(text, triggers)
            if message and time.monotonic() - last_sent >= cooldown:
                send_udp(host, port, message, encoding)
                last_sent = time.monotonic()

    device = cfg.get("device")  # null で既定マイク。マイク一覧は `python -m sounddevice`
    with sd.InputStream(
        samplerate=SAMPLE_RATE,
        channels=1,
        dtype="int16",
        blocksize=BLOCK_SIZE,
        device=device,
        callback=callback,
    ):
        while True:
            time.sleep(0.1)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n終了しました。")
