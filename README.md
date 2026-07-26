# ContinuousPlayer

フォルダ内の動画ファイルを連続再生する Android アプリケーションです。USB DAC 接続時のビットパーフェクト音声出力に対応しています。

## 主な機能

- **フォルダ連続再生** — 選択したフォルダ内の `.mp4` / `.m4v` ファイルをプレイリストとして連続再生
- **ビットパーフェクト音声出力** — USB DAC 接続時に Android ミキサーをバイパスし、ビットパーフェクト出力を実現（対応デバイスのみ）
- **ジェスチャー操作** — フルスクリーン表示でタッチジェスチャーによる直感的な操作
- **SAF (Storage Access Framework)** — 内部ストレージ・USB ストレージ・SD カードなど多様なストレージに対応
- **エラー時自動スキップ** — 再生エラー発生時は自動的に次の動画へスキップ

## 動作環境

- Android 14 (API 34) 以上
- Target SDK: 36

## ジェスチャー操作

| ジェスチャー | 動作 |
|---|---|
| シングルタップ | 再生 / 一時停止 |
| ダブルタップ（右半分） | 15秒早送り |
| ダブルタップ（左半分） | 10秒巻き戻し |
| 右スワイプ | 前の動画 |
| 左スワイプ | 次の動画 |

## 一時停止オーバーレイ

一時停止中は画面上部にツールバーが表示されます。

- **ファイル名** — 再生中の動画ファイル名
- **情報ボタン** — 音声出力デバイス情報の表示
- **フォルダ選択ボタン** — 別のフォルダ / ファイルを選択
- **閉じるボタン** — フォルダ選択画面に戻る

## ファイル選択フロー

1. **ツリーピッカー** — フォルダへのアクセス権限を取得
2. **ファイルピッカー** — 再生開始する動画ファイルを選択
3. 選択したファイルを含むフォルダをスキャンし、プレイリストを作成して再生開始

権限を持たないフォルダのファイルを選択した場合は、再度ツリーピッカーが表示されます。

## ビルド

```bash
# デバッグビルド
./gradlew assembleDebug

# リリースビルド
./gradlew assembleRelease

# ユニットテスト
./gradlew test

# インストゥルメンテッドテスト（デバイス / エミュレータ必要）
./gradlew connectedAndroidTest
```

## プロジェクト構成

```
app/src/main/java/jp/nagu/continuousplayer/
├── MainActivity.kt            # メインActivity（画面管理・ファイル選択フロー）
├── PlayerController.kt        # ExoPlayerラッパー（再生制御・プレイリスト管理）
├── PlayerViewModel.kt         # ViewModel（構成変更時の状態保持）
├── VideoScanner.kt            # SAFベースのフォルダスキャナー
├── VideoItem.kt               # 動画ファイルのデータクラス
├── GestureHandler.kt          # タッチジェスチャー処理
└── BitPerfectAudioManager.kt  # USB DACビットパーフェクト音声出力管理
```

## 技術スタック

- **言語**: Kotlin
- **メディア再生**: Media3 ExoPlayer
- **ストレージ**: Storage Access Framework (DocumentFile)
- **音声出力**: AudioManager + AudioMixerAttributes
- **ビルド**: Gradle 9.2.1 / AGP 9.0.1

## ライセンス

All rights reserved.
