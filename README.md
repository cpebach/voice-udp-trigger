# voice-udp-trigger

音声でキーワードを話すと、指定した IP:ポートへ UDP メッセージを送るアプリ。
音声認識は [Vosk](https://alphacephei.com/vosk/) による完全オフライン(インターネット不要・クラウド送信なし)。

## 必要環境

- Python 3.9+
- マイク

## セットアップ

```bash
# 1. 依存インストール
pip install -r requirements.txt

# 2. 日本語音声モデルを取得(約42MB)
#    https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip
#    をダウンロードして解凍し、リポジトリ直下の ./model に配置

# 3. 送信先とキーワードを設定
#    config.json を編集(下表参照)

# 4. 起動
python main.py
```

## config.json の設定

| キー | 説明 |
|---|---|
| `target_host` | 送信先IPアドレス |
| `target_port` | 送信先UDPポート |
| `triggers` | `"認識させたい語": "送るメッセージ"` の対応表。認識テキストに語が含まれていれば送信 |
| `cooldown_seconds` | 連続発火の抑止時間(秒)。既定 2.0 |
| `device` | 入力デバイス番号(`null` で既定マイク。一覧は `python -m sounddevice`) |
| `encoding` | メッセージの文字コード(既定 `utf-8`) |
| `model_path` | Voskモデルのパス(既定 `model`) |

## 動作テスト

受信側でもう一台(または同一マシン)で待ち受ける:

```bash
python -c "import socket; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.bind(('0.0.0.0',5000)); \
[print(d.decode(), 'from', a) for d,a in iter(lambda: s.recvfrom(1024), None)]"
```

アプリを起動して `triggers` に書いたキーワードを発話し、届けばOK。

## 📱 Android版

`android/` にKotlin版アプリがある。フォアグラウンドサービスで継続聞き取りし、
キーワード検出でUDP送信(設定は画面から入力、2秒クールダウン)。

### ビルド

```bash
cd android
gradle assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk (音声モデル同梱で約117MB)
```

要: JDK 17 / Android SDK (platform-34, build-tools 34.0.0)。
音声モデル `vosk-model-small-ja-0.22` を `app/src/main/assets/model/` に展開して同梱(このディレクトリはgitignore済み)。

**重要**: `app/src/main/assets/model/uuid` に識別子ファイルが必要(無いと起動時に
`FileNotFoundException: model/uuid` で失敗する)。中身は任意のUUID文字列でOK:

```bash
python -c "import uuid; open('android/app/src/main/assets/model/uuid','w').write(str(uuid.uuid4()))"
```

### インストール

`app-debug.apk` を転送してインストール(提供元不明アプリの許可が必要)。
初回起動時にマイクと通知の権限を許可 → IP/ポート/キーワード/メッセージを入力 → 開始。

## 仕組み

1. マイクから 16kHz モノラルで音声を取り込み
2. Vosk がストリーム認識し、発話の区切りでテキストを出力
3. テキストがキーワードを含んでいれば UDP データグラムを1通送信
   (クールダウン内での重複発火は防止)

> UDP は到達保証のない一方送信。相手が同じLAN内のスマート照明・工作機材・ゲーム等に向いている。
