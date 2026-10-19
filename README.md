# spring-ai-local-llm-chat

Spring AI ChatClientからローカルLLMを利用するためのサンプルです。**このコミットでは、まずOpenVINO Model Server（OVMS）の起動と単体検証のみ**を行います。Spring Boot・Spring AI・GUIからの呼び出しは次のステップで実装します。

## 今回の構成

```text
CLI (curl)
    |
    | OpenAI互換API  http://127.0.0.1:8000/v1
    v
OVMS 2026.4.0 (Docker Compose)
    |
    v
Gemma 4 E4B INT4 (Intel GPU)
```

- 推論モデル：`OpenVINO/gemma-4-E4B-it-int4-ov`
- API上のモデル名：`gemma4`
- コンテナイメージ：`openvino/model_server:2026.4.0-gpu`
- 対応環境：**Windows + WSL2 + Docker Compose + Intel GPU** を主な検証対象としています。
- `compose.yaml` はWSL2用です。Linuxで`/dev/dri`を使用する環境ではGPUデバイスとドライバーマウントを変更してください。

> **注意：** Gemma 4 E4BのOpenVINO変換済みモデルは、公開元で実験的（EXPERIMENTAL）と記載されています。起動・画像推論の実動作は利用環境によって異なり、必ずしも成功するとは限りません。

## 1. 前提条件

- WSL2のLinuxディストリビューションから`docker compose`を利用できること
- Intel GPUドライバーが導入されていること
- WSL2側で`/dev/dxg`、`/usr/lib/wsl`が利用できること
- モデルのダウンロードが可能なインターネット接続、数GB以上の空き容量があること

WSL2ターミナルで確認します。

```bash
docker compose version
ls -l /dev/dxg
ls -ld /usr/lib/wsl
```

> `/dev/dxg`が見えない場合、Composeを起動する前にWindows側のGPUドライバー／WSL2／Docker環境を確認してください。Linuxネイティブ（`/dev/dri`）と同じ設定は使えません。

## 2. モデルサーバーを起動する

リポジトリのルートで実行します。

```bash
# 省略可。モデルを切り替えたい場合に編集するファイル
cp .env.example .env
# 初回に.envを作った場合は、既存の.gitignoreに.envを追加してください

# .envにコンテナ実行用のUID/GIDを設定（ホストユーザーに合わせる）
echo "LOCAL_UID=$(id -u)" >> .env
echo "LOCAL_GID=$(id -g)" >> .env

# モデルの格納ディレクトリを作成し、ホストユーザーが書き込めるようにする
mkdir -p models
sudo chown -R "$(id -u):$(id -g)" ./models
chmod -R u+rwX ./models

# Composeファイルの解釈結果を確認
docker compose config

# OVMSをバックグラウンド起動
docker compose up -d

# 初回はモデルダウンロードとロードがあるため、ログを確認
docker compose logs -f ovms
```

`docker compose logs -f ovms`は`Ctrl+C`で表示を終了しても、コンテナは停止しません。

**Gemma 4の初回起動時はモデルがダウンロードされます。** ダウンロードされたデータはホストの`./models`ディレクトリ（コンテナ内の`/models`にバインドマウント）に保存されます。コンテナは`.env`の`LOCAL_UID`/`LOCAL_GID`（ホストユーザー）で実行されるため、ダウンロードしたファイルの所有者はホストユーザーになります。モデルを削除したい場合は`./models`の中身を直接削除してください。

APIはホストの`127.0.0.1:8000`にのみ公開しています。

## 3. コマンドラインで動作確認する

以下は**WSL2のLinuxシェル**での実行例です（Windows PowerShellでは引用符・コマンドの構文が異なります）。

### 3-1. モデル一覧

```bash
curl --fail-with-body -sS http://127.0.0.1:8000/v1/models
```

レスポンスの`data`配列に`"id":"gemma4"`が表示されれば、モデルの公開状態を確認できます。ただし、これだけでは生成・画像解析の成功までは保証しません。

### 3-2. 日本語でテキスト推論

```bash
curl --fail-with-body -sS http://127.0.0.1:8000/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "gemma4",
    "messages": [
      {"role": "user", "content": "こんにちは。自己紹介を日本語で二文でお願いします。"}
    ],
    "max_completion_tokens": 128,
    "stream": false
  }'
```

レスポンスの`choices[0].message.content`に日本語の回答が入れば、テキスト推論は成功です。

### 3-3. 画像解析を確認する

プロジェクトルートに任意のPNGファイル（例：`sample.png`）を用意してください。下記のコマンドはPython標準ライブラリを使って画像をBase64化し、そのままOVMSへPOSTします。Pythonの追加パッケージやテスト用スクリプトは不要です。

```bash
python3 - <<'PY' | curl --fail-with-body -sS \
  http://127.0.0.1:8000/v1/chat/completions \
  -H 'Content-Type: application/json' --data-binary @-
import base64
import json
from pathlib import Path

image = Path('sample.png')
encoded = base64.b64encode(image.read_bytes()).decode('ascii')
request = {
    'model': 'gemma4',
    'messages': [{
        'role': 'user',
        'content': [
            {'type': 'text', 'text': 'この画像には何が写っていますか？ 日本語で答えてください。'},
            {'type': 'image_url', 'image_url': {'url': f'data:image/png;base64,{encoded}'}}
        ]
    }],
    'max_completion_tokens': 200,
    'stream': False,
}
print(json.dumps(request, ensure_ascii=False))
PY
```

JPEGを使う場合は`Path('sample.jpg')`に変更し、`data:image/png`を`data:image/jpeg`に変更してください。画像内容を説明する応答が返れば、VLMとしての基本動作を確認できます。

## 4. モデルを切り替える

モデル切り替えは`.env`の**2項目**を変更します。`MODEL_SOURCE`はHugging Face上のモデル、`MODEL_NAME`はOVMSが公開するAPI上の名前です。

### Gemma 4 E4B（標準）

```dotenv
MODEL_SOURCE=OpenVINO/gemma-4-E4B-it-int4-ov
MODEL_NAME=gemma4
```

### Qwen3.5-9B INT4

```dotenv
MODEL_SOURCE=OpenVINO/Qwen3.5-9B-int4-ov
MODEL_NAME=qwen35-9b
```

設定変更後は次のようにコンテナを再作成します。

```bash
docker compose up -d --force-recreate
docker compose logs -f ovms
curl --fail-with-body -sS http://127.0.0.1:8000/v1/models
```

モデルを変更したあとは、上のテキスト・画像の検証リクエストに書かれた`"model": "gemma4"`も、`.env`の`MODEL_NAME`に合わせて変更してください。後でSpring AIから接続するときも同じモデル名を指定します。

**注意：** GPUで動くか、特に画像入力が動くかはモデルとOVMSの組み合わせで確認が必要です。前に動かせたQwen3.5-9Bでも、今回のCompose設定での動作は改めて確認してください。

## 5. 停止・トラブルシューティング

```bash
docker compose ps
docker compose logs --tail=100 ovms
docker compose down
```

- `failed to make directory '/models/...': Permission denied` / `Libgit2 clone error`：コンテナの実行ユーザーが`/models`に書き込めません。`.env`の`LOCAL_UID`/`LOCAL_GID`がホストユーザーと一致しているか、ホスト側の`./models`の所有者・権限（`chown`/`chmod`の手順）を確認してください。
- `cannot access /dev/dxg`：WSL2のデバイス公開、GPUドライバー、Docker実行場所を確認してください。
- `CL_OUT_OF_RESOURCES`：GPUメモリ不足の可能性があります。実行中の別LLMを止め、軽量モデルで再確認してください。
- `Unsupported ... model type`：OVMSとモデル形式の非互換が疑われます。コンテナのバージョン・モデルカード・OVMSログを確認してください。
- `connection refused`：コンテナが停止しているか、ロード中の可能性があります。`docker compose ps`とログを確認してください。
- `model not found`：APIのモデル名が`MODEL_NAME`と一致するか、`/v1/models`で確認してください。
- **すでに別のOVMSを使っている場合**：ホストの8000番ポートが重複しないよう、既存OVMSを停止するかポートを変更してください。

## この後の実装

ここまでは**Spring Bootに依存しません**。OVMSの単体動作を確認したら、次のステップでSpring AI ChatClientを接続し、さらにThymeleaf + HTMXのGUI、画像添付、会話履歴を追加します。

将来的には`spring-boot-docker-compose`によって`./mvnw spring-boot:run`からComposeを起動できますが、最初の検証段階では明示的に`docker compose up -d`を使って、障害原因を切り分けやすくしています。

## 参考資料

- [OVMS — Using AI Accelerators](https://docs.openvino.ai/2026/model-server/ovms_docs_target_devices.html)
- [OVMS — Starting the Server](https://docs.openvino.ai/2026/model-server/ovms_docs_serving_model.html)
- [OVMS — VLM models via OpenAI API](https://docs.openvino.ai/2026/model-server/ovms_demos_continuous_batching_vlm.html)
- [OpenVINO Gemma 4 E4B INT4 (Hugging Face)](https://huggingface.co/OpenVINO/gemma-4-E4B-it-int4-ov)
- [OpenVINO Qwen3.5-9B INT4 (Hugging Face)](https://huggingface.co/OpenVINO/Qwen3.5-9B-int4-ov)
