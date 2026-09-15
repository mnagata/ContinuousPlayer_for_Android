# ContinuousPlayer — 現行実装仕様書

本書はリポジトリ内の実装・リソース・ビルド設定に基づく。未実装の構想や実機検証済みであることを示すものではない。

## 1. 概要と動作環境

TVアニメのOP/EDを中心に、選択したフォルダーの動画・音声ファイルを連続再生するAndroidアプリ。スマートフォン・タブレットとAndroid TVで同一APKを使用する。

| 項目 | 現行設定 |
|---|---|
| アプリ名 / applicationId | ContinuousPlayer / jp.nagu.continuousplayer |
| 最低OS | Android 14（API 34） |
| compileSdk / targetSdk | 36（minorApiLevel 1）/ 36 |
| versionCode / versionName | 1 / 1.0 |
| 構成 | 単一 `:app` モジュール、Kotlin、XML View |
| Gradle / Android Gradle Plugin | 9.6.1 / 9.4.0 |
| Gradle Daemon JVM / コンパイル出力 | JDK 21 / Java・Kotlin JVM 11 |
| 再生基盤 | Media3 ExoPlayer + MediaSession |

バージョンの参照元は `app/build.gradle.kts`、`gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties`、`gradle/gradle-daemon-jvm.properties`。

## 2. 起動と画面遷移

```text
アプリ起動 → システムスプラッシュ → 初期画面
初期画面「OP / EDを選ぶ」（スマートフォン）
  → 読み取り永続権限なし：フォルダー選択 → ファイル選択
  → 読み取り永続権限あり：ファイル選択
  → 対象フォルダーのスキャン → 選択ファイルから連続再生
初期画面「OP / EDを選ぶ」（TV）
  → 未許可ならアクセス許可の説明 → OS設定 → アプリに戻る
  → USB / 外部ストレージ一覧 → フォルダー → ファイル選択 → 連続再生
再生画面「戻る」 → 再生を終了して初期画面
再生画面「フォルダー選択」 → 再生を終了して初期画面 → 選択フロー
初期画面「Exit」/ 再生画面「終了」 → finishAndRemoveTask()
初期画面「戻る」 → OS標準の戻る処理
```

通常起動ではファイルピッカーを自動起動しない。初期画面のボタンから選択を開始する。ピッカーをキャンセルした場合、結果がnullのため処理を進めない。

### 2.1 スプラッシュ画面

- Android標準の `android.window.SplashScreen` APIを使用。互換ライブラリや専用Activityは追加していない。
- Manifestで `MainActivity` に `Theme.ContinuousPlayer.Starting` を指定。
- 背景は濃紺 `#101743`、中央マークは `@drawable/ic_launcher_foreground`。
- `windowSplashScreenBehavior=icon_preferred`。
- `onCreate()` の `super.onCreate()` より前に通常テーマへ切り替える。
- TV以外では終了時に180msのフェードアウトを行い、終了後にスプラッシュViewを削除する。
- TVでは独自の終了アニメーションを登録せず、システムの起動演出に従う。
- 最低表示時間や意図的な待機時間は設定していない。

## 3. 初期画面

`activity_main.xml` の `folder_select_container` を表示する。

| 要素 | 表示・動作 |
|---|---|
| 背景 | 濃紺を中心にシアン系・紫系をつないだグラデーション |
| ロゴ | ランチャーアイコンの前景ベクターを共用。装飾としてアクセシビリティ対象外 |
| 英字ラベル | ANIME OPENINGS / ENDINGS |
| アプリ名 | ContinuousPlayer、白、1行、自動文字サイズ調整20〜32sp |
| メインボタン | 「OP / EDを選ぶ」、280×56dp、青緑〜紫のグラデーション |
| 終了ボタン | 「Exit」、280×48dp |
| TV操作ガイド | TV端末のみ表示。左右シーク・上下前後移動・決定再生/一時停止を案内 |
| キャッチコピー | 表示しない |

- 内容は中央揃えの縦配置。ScrollViewで狭い横画面などでもスクロール可能。
- 左右余白は28dp。通常はロゴ180dp・上下余白32dp、横画面ではロゴ100dp・上下余白20dp。
- TVでは初期画面表示時に「OP / EDを選ぶ」へフォーカスする。
- 選択・押下状態は専用ボタン背景で区別。上下で選択ボタンとExitの間を移動する。
- 再生画面と異なり、初期画面ではシステムバーを表示する。

## 4. 再生画面

黒背景の `player_container` 内に以下を配置する。

| 要素 | 仕様 |
|---|---|
| PlayerView | 全画面、Media3標準コントローラは無効、再生中のバッファリング表示を有効 |
| touch_overlay | 全面の透明Viewでタップ・フリングを受ける |
| pause_overlay | 上端48dp、半透明黒、ファイル名と4つの操作ボタン |
| portrait_video_info | 縦画面のみ、下端から120dpの位置にメディア情報を表示 |

ステータスバーとナビゲーションバーを隠し、スワイプによる一時表示を許可する。再生画面でウィンドウフォーカスを取り戻したときもシステムバーを隠す。プレイヤー生成中は `keepScreenOn=true`、プレイヤー解放時に解除する。

### 4.1 一時停止ツールバー

表示条件は厳密には `player.isPlaying == false`。ユーザーの一時停止だけでなく、終了・バッファリングなどでも表示される場合がある。

左にファイル名（1行、中央省略）、右に次のボタンを表示する。

1. 再生再開
2. 情報ダイアログ
3. フォルダー選択
4. アプリ終了

ボタンは各48dp。左右フォーカスは上記の順で循環する。TVではツールバー表示時に内部のフォーカスがなければ再生再開ボタンへ移し、再生開始時はPlayerViewへ戻す。ツールバーのフォーカス背景は緑色と白枠で、初期画面用の配色とは別リソース。

### 4.2 情報表示

縦画面の情報欄には、取得できた項目を表示する。

- 拡張子を除いたファイル名、ファイルサイズ
- 動画コーデック、解像度、フレームレート
- 音声コーデック、サンプルレート、チャンネル数
- 音声出力の概要とミキサー情報

情報ダイアログにはファイル名、音声出力デバイス、Bit-Perfect設定状態、ソース音声の形式、取得可能なミキサー属性を表示する。「OK」で閉じる。

## 5. 操作仕様

### 5.1 タッチ操作

| 操作 | 動作 |
|---|---|
| シングル/ダブルタップ：左1/3 | 10秒巻き戻し |
| シングル/ダブルタップ：中央1/3 | 再生 / 一時停止 |
| シングル/ダブルタップ：右1/3 | 10秒早送り |
| 右フリング | 前のファイル |
| 左フリング | 次のファイル |

フリングは横移動が縦移動より大きく、移動距離100px・速度100px/秒をともに超える場合に判定する。

### 5.2 リモコン・キー操作

再生画面で `dispatchKeyEvent()` が初回のACTION_DOWN（repeatCount=0）を処理する。それ以外は標準処理へ渡す。キー処理自体はTV判定で限定していない。

| キー | 通常の再生操作 | 非再生中かつツールバー内にフォーカスがある場合 |
|---|---|---|
| 決定 / Enter / テンキーEnter / A | 再生 / 一時停止 | フォーカス中ボタンの標準処理 |
| D-pad左 / 右 | 10秒巻き戻し / 早送り | 標準フォーカス移動 |
| D-pad上 / 下 | 前 / 次のファイル | 同じ |
| MEDIA_PREVIOUS / NEXT | 前 / 次のファイル | 同じ |
| MEDIA_PLAY_PAUSE | 再生 / 一時停止 | 同じ |
| MEDIA_PLAY / PAUSE | 明示的に再生 / 一時停止 | 同じ |
| MEDIA_REWIND / FAST_FORWARD | 10秒巻き戻し / 早送り | Activityの独自シークは行わず標準処理へ渡す |
| INFO | 情報ダイアログ | 同じ |

音量キーは独自に処理しない。MediaSessionもExoPlayerに接続されている。

## 6. Android TV対応

- 同一MainActivityを通常の `LAUNCHER` と `LEANBACK_LAUNCHER` に登録。
- `android.software.leanback` と `android.hardware.touchscreen` はともに `required=false`。
- TV判定はConfigurationの `UI_MODE_TYPE_TELEVISION`。
- TVホーム用バナーをApplicationの `android:banner` に指定。
- TV専用Activityはなく、共通レイアウトと横画面向け寸法を使用する。画面方向の固定指定はない。
- 最低OSはTVもAPI 34。古いTV OSへの互換対応は実装していない。
- TVではシステムピッカーを呼ばず、アプリ内のUSBブラウザーを使用する。
- スマートフォンはSAFを使用し、初回起動要求でActivityNotFoundExceptionが発生した場合はUSBブラウザーへ進む。

### 6.1 USBブラウザー

- 対象：Nebula X1など、システムのフォルダー選択アプリを利用できないTVでのUSB再生。
- 「すべてのファイルへのアクセス」が必要。共有ストレージ全体への読み書きが可能になる許可であることを説明し、ユーザーが設定を開く操作を選んだ場合だけOS設定へ進む。実装は読み取りのみ。
- アプリ個別のアクセス許可設定を開き、起動できなければ全アプリの許可一覧を試す。どちらも起動できなければ手動設定の案内を表示する。
- 設定画面から戻った際に許可を再確認。許可済みなら一覧を開き、拒否された場合は案内して終了する。
- StorageManager.storageVolumesから、取り外し可能かつマウント済み（読み取り専用を含む）のストレージを列挙する。OSにマウントされていないUSBには対応しない。
- ダイアログの一覧をD-padで操作。フォルダーを先に名前順、次に対応メディアをOP/ED順で表示する。
- 「上の階層へ」または戻るキーで親へ移動し、ルートからはドライブ一覧へ戻る。「キャンセル」で閉じる。
- ディレクトリ探索はドライブのルート内に制限。IO処理はバックグラウンドで実行し、読み込み中のキャンセルとActivity破棄時の解放に対応する。
- ファイルを選ぶと、そのフォルダー直下のプレイリストをfile URIで再生する。ファイルのコピー・削除・書き込みは行わない。
- USB未接続、空フォルダー、読み取り失敗、権限拒否には案内を表示する。
- Nebula X1実機でのUSB認識、許可画面、再生は別途確認が必要。

## 7. ファイル選択・スキャン・並び順

### 7.1 スマートフォンのSAF選択フロー

1. 永続化された読み取りURI権限が1件でもあれば `OpenDocument`、なければ `OpenDocumentTree` を起動する。
2. ツリー選択後、読み取り・書き込みの永続権限取得を試みる。SecurityExceptionはログに記録し、その後ファイル選択へ進む。
3. ファイル選択のMIMEフィルターは `video/*` と `audio/*`。
4. 選択ファイルのdocument IDとツリーIDの一致またはパス接頭辞一致で、読み取り権限を探す。
5. 対応ツリーがなければ再度ツリー選択へ進む。権限取得後はファイルも再選択する。
6. 対応ツリー内の親ディレクトリを求め、IOディスパッチャーでスキャンする。
7. 0件なら「No video files found」を表示。存在すればプレイリストを設定して再生画面へ移る。

document IDをパス形式として扱う実装であり、任意のDocumentsProviderで同じ動作を保証するものではない。

### 7.2 対象ファイル

- USB・SAF共通で、名前が `._` から始まるmacOSのAppleDoubleメタデータファイルを一覧・プレイリストから除外する。例：`._作品 OP.mp4` は除外し、`作品 OP.mp4` は対象とする。
- 両方式とも通常ファイルだけを対象にする。通常の隠しファイルは名前だけでは除外せず、対応拡張子なら対象とする。除外ファイルをストレージから削除・変更する処理はない。

- 選択ファイルの親ディレクトリ直下のファイルを列挙する。
- ツリールートから対象ディレクトリまでの探索は行うが、対象ディレクトリ配下の再帰スキャンは行わない。
- 拡張子は大文字小文字を区別せず、以下を対象とする。

`.mp4`, `.m4v`, `.mp3`, `.flac`, `.m4a`, `.aac`, `.wav`, `.ogg`, `.opus`

拡張子での列挙対象と、端末で実際にデコードできる形式は別である。

### 7.3 OP/EDの並び順

1. 全ファイルを端末の既定LocaleのCollator（SECONDARY）で名前の昇順に並べる。
2. 拡張子を除いた名前が `^(.+?)\s+(OP|ED)(\d*)$` に一致するものを抽出する（大文字小文字を区別しない）。
3. OP/EDより前のベース名でまとめ、番号の昇順、同番号ではOP→EDの順にする。番号省略は1として扱う。
4. 各グループが元々占めていた位置だけを入れ替える。非該当ファイルの位置は維持する。

例：同一作品の該当ファイルは `作品 OP → 作品 ED → 作品 OP2 → 作品 ED2` の順となる。本編をOPとEDの間に挿入する処理はない。

開始位置はdocument ID一致、次にファイル名の大文字小文字を無視した一致で探す。見つからなければ先頭を使用する。

## 8. 再生と状態管理

- ExoPlayerへURIのMediaItem一覧を渡し、選択位置の0msからprepare・playする。
- リピートはOFF。次の項目へ自動遷移し、最終項目で終了する。
- 前後移動は該当項目が存在する場合だけ実行する。
- 巻き戻しは0ms未満にならない。
- 早送りで既知の再生時間の末尾に達する場合、次の項目があれば移動し、なければ何もしない。
- 独自の先読み・クロスフェード・ギャップレス保証はない。

### 8.1 エラー処理

再生エラー時は次の項目があれば移動してprepareする。エラーカウントが3に達した場合、または末尾でエラーになった場合は自動スキップせず、カウントをリセットする。

成功再生時にカウントをリセットする実装ではないため、「3回連続エラー」という仕様ではない。3回到達時に明示的なstop呼び出しも行わない。

### 8.2 ライフサイクル・保存範囲

- `onStop()`：一時停止。
- `onStart()`：再生画面フラグがtrueならplay。退避前の一時停止状態を区別しない。
- `onDestroy()`：プレイヤー、MediaSession、音声設定を解放し、再生画面フラグをfalseにする。
- Manifestでorientation・screenSize・keyboardHiddenの構成変更をActivity自身が処理する。
- ViewModelが保持するのはメモリー内の動画一覧と再生画面フラグのみ。
- 再生位置・選択インデックスの保存、SavedStateHandle、プロセス終了後のプレイリスト復元は実装していない。
- Activity再生成後の復元処理はあるが、startPlayback内で画面フラグをリセットするため、完全な復元は保証されない。
- SAFのアクセス権限は永続化するが、これは再生履歴の保存とは別。
- バックグラウンド再生サービスはない。

## 9. USBビットパーフェクト音声設定

`BitPerfectAudioManager` がUSB出力に対して対応ミキサーの設定を試みる。

1. USB_DEVICEまたはUSB_HEADSETの出力デバイスを探す。
2. BIT_PERFECT対応ミキサー属性を取得する。
3. MediaExtractorで再生開始ファイルのサンプルレート・チャンネル数を抽出する。
4. 両者が一致する属性を優先し、なければ最初のBIT_PERFECT属性を選ぶ。
5. USAGE_MEDIA / CONTENT_TYPE_MUSICでsetPreferredMixerAttributesを実行する。
6. 成功時はactiveDeviceを保持。解放時にclearPreferredMixerAttributesを呼ぶ。

非対応デバイスや設定失敗時は設定を適用しない。処理は例外をログに記録する。

設定はプレイヤー生成時の開始ファイルについて実行される。項目切り替えごとの再設定はない。表示上の「Bit-Perfect」は設定成功状態を表し、実出力のビット一致を測定した結果ではない。HDMIなどを含むすべての出力経路でのビットパーフェクト動作は保証しない。

## 10. デザイン資産

アイコンは「重なる映像フレーム・白い再生マーク・次へ進む矢印」をモチーフとし、濃紺にシアン・青・紫・ピンクを組み合わせる。

以下のパスは `app/src/main/res/` 基準。

| パス | 用途 |
|---|---|
| drawable/ic_launcher_foreground.xml | 前景ベクター。ランチャー・スプラッシュ・初期画面で共用 |
| drawable/ic_launcher_background.xml | アイコンの濃紺背景 |
| mipmap-anydpi/ic_launcher.xml | 通常Adaptive Icon |
| mipmap-anydpi/ic_launcher_round.xml | 丸形Adaptive Icon |
| drawable-xhdpi/tv_banner.png | 320×180px、同モチーフとContinuousPlayerの文字を含むTVホーム用バナー |
| drawable/home_background.xml | 初期画面グラデーション |
| drawable/home_primary_button.xml | メインボタンの通常・フォーカス・押下状態 |
| drawable/home_secondary_button.xml | Exitボタンの状態表示 |
| drawable/player_button_background.xml | 再生ツールバーの状態表示 |
| drawable/ic_play.xml | 再生再開ボタン |
| values/themes.xml | 通常テーマとスプラッシュテーマ |
| values/dimens.xml / values-land/dimens.xml | 通常・横画面の初期画面寸法 |

Adaptive Iconのmonochromeにも同じ前景ベクターを参照する。各密度の旧ランチャーWebPも残っているが、現行対応OSではanydpiのAdaptive Icon定義を使用する。

## 11. 実装構成

| クラス | 責務 |
|---|---|
| MainActivity | 初期画面・再生画面・スプラッシュ・キー入力・SAF選択・情報表示 |
| PlayerController | ExoPlayer、MediaSession、プレイリスト、シーク、エラー時スキップ |
| VideoScanner | DocumentFileでの列挙、拡張子フィルター、OP/ED整列 |
| UsbFileBrowser | 外部ストレージ一覧、読み取り専用のフォルダー探索、file URIプレイリスト選択 |
| GestureHandler | タッチ位置とフリング判定 |
| BitPerfectAudioManager | USBミキサー設定と出力情報 |
| PlayerViewModel | メモリー内の一覧と再生画面フラグ |
| VideoItem | uri、displayName、size、lastModified |

Kotlinソースは `app/src/main/java/jp/nagu/continuousplayer/`、Manifestは `app/src/main/AndroidManifest.xml` に置く。

## 12. 依存関係・権限

| 依存関係 | 設定バージョン |
|---|---|
| core-ktx | 1.10.1 |
| appcompat | 1.7.0 |
| activity-ktx | 1.9.3 |
| lifecycle-viewmodel-ktx | 2.8.7 |
| documentfile | 1.1.0 |
| media3-exoplayer / ui / session | 1.5.1 |
| media3-common-ktx | 1.9.2 |
| gson | 2.11.0 |
| JUnit | 4.13.2 |
| AndroidX Test JUnit / Espresso | 1.1.5 / 3.5.1 |

バージョンカタログの宣言値であり、推移依存の最終解決バージョンとは区別する。Kotlinプラグイン2.1.0のエイリアス定義はあるが、appではAndroid applicationプラグインのみ適用している。

アプリManifestで直接宣言する権限は `MODIFY_AUDIO_SETTINGS` と `MANAGE_EXTERNAL_STORAGE`。スマートフォンの通常選択はSAFのURI権限、TVのUSBブラウザーはユーザーが許可する全ファイルアクセスを使用する。依存ライブラリのManifestによる権限が最終APKへ追加される場合がある。

`MANAGE_EXTERNAL_STORAGE` はGoogle Playで制限対象の権限である。同一APKをPlay配布する場合、用途の適格性確認・申告を要し、このUSB対応を追加しただけで公開要件を満たすとは限らない。

## 13. ビルドと確認

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedAndroidTest
./gradlew assembleRelease
```

デバッグAPK：`app/build/outputs/apk/debug/app-debug.apk`。端末へ反映するにはビルド後に更新インストールする。リリースの署名設定はapp/build.gradle.ktsには定義されていない。

手動確認項目：

1. 通常起動でスプラッシュから初期画面に移り、キャッチコピーを表示しない。
2. 「OP / EDを選ぶ」から権限取得・ファイル選択・再生へ進める。
3. 同一フォルダー直下の対象ファイルを仕様のOP/ED順で再生する。
4. タッチとD-padで再生・停止・前後移動を操作できる。
5. ツールバーの全ボタンへフォーカスを移せる。
6. スマートフォンの縦横表示とTVランチャー・バナー・ピッカーを確認する。
7. 最終項目、エラー、戻る、バックグラウンド移行時の挙動を確認する。
8. USB音声設定は対応実機で確認する。

ビルドやLintの成功は、TV実機のファイルピッカーやUSB音声の動作検証を意味しない。
