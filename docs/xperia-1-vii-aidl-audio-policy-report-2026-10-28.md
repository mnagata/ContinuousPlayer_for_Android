# Xperia 1 VII 音声ポリシー・全ポート調査報告書

作成日：2026年10月28日（日本時間）。対象：ユーザー指定のXperia 1 VII。実機から取得したモデル識別子は`XQ-GE44` / device `XQ-GE`。

USB・イヤホン端子・内蔵スピーカー・Bluetooth・入力系を含む全MixPort、全宣言デバイスポート、接続時に展開された動的ポート、全経路定義を本書内に収録した。本文の表だけで確認できる構成とし、別のCSV・JSON・生ログを読むことを前提としない。

## 目次

1. [調査対象・時刻・確認方法](#scope)
2. [全体構成と出力先別の要点](#overview)
3. [USB DAC・USB入力・USB Accessory](#usb)
4. [イヤホン端子・内蔵スピーカー](#analog)
5. [Bluetoothの設定とBTR17再生時の実測](#bluetooth)
6. [入力・録音・通話・ホットワード](#input)
7. [全MixPort：出力20・入力9](#mixports)
8. [全宣言デバイスポート：43](#devices)
9. [動的ポートと接続後の差分](#dynamic)
10. [全経路定義：37・展開接続268](#routes)
11. [用途別選択・音量・エフェクト](#engine)
12. [資料の根拠と未確認範囲](#limits)

<a id="scope"></a>

## 1. 調査対象・時刻・確認方法

| 項目                 | 取得内容                                                                                               |
| -------------------- | ------------------------------------------------------------------------------------------------------ |
| OS                   | Android 17 / API 37                                                                                    |
| ビルド               | `Sony/XQ-GE44/XQ-GE:17/73.1.A.2.74/073001A002007400986449180:user/release-keys`                        |
| 初回スナップショット | 2026-10-28 13:26:31 JST。FX-D03J+ L2のUSB接続あり、Bluetooth音声接続なし、再生停止中                   |
| Bluetooth接続後      | 2026-10-28 13:36:16 JST前後。BTR17をA2DP接続し、ContinuousPlayer再生中                                 |
| 再確認               | 2026-10-28 13:38:26 JST。AudioPolicyの全出力active count=0                                             |
| 設定の取得元         | AudioPolicyManager：`Config source: AIDL HAL`                                                          |
| 操作範囲             | ADBによる設定・状態の読み取り。アプリ更新、再生開始、接続変更、音量・Bluetooth設定変更は実施していない |

各dumpは順次取得であり、厳密な同時測定ではない。時刻はすべて日本時間。本書の「設定上の対応」は取得時のポリシー宣言、「動的プロファイル」は接続後に展開された値、「再生中の実測」はactive clientとAudioFlingerの状態で裏付けた値を示す。

| HAL系統         | VINTF宣言                          | 登録サービス                                                                                 |
| --------------- | ---------------------------------- | -------------------------------------------------------------------------------------------- |
| Audio Core      | v3                                 | `IConfig/default`、`IModule/default`、`IModule/usb`、`IModule/r_submix`、`IModule/bluetooth` |
| Audio Effect    | v3                                 | `IFactory/default`                                                                           |
| Bluetooth Audio | v5                                 | `IBluetoothAudioProviderFactory/default`                                                     |
| SoundTrigger    | AIDL（取得VINTFにversion記載なし） | `android.hardware.soundtrigger3.ISoundTriggerHw/default`                                     |

AIDLサービスへの直接`dumpsys`は`FAILED_TRANSACTION`で取得できなかった。実効ポート・経路はAudioPolicyがHALから取り込んだダンプを基準とし、AudioFlinger、Bluetoothスタック、端末内XML/JSON、サービス登録、VINTF宣言を照合した。

primaryのAIDL形式設定候補`/vendor/etc/audio_module_config_primary.xml`は、実効primaryのMixPort名24件と経路接続集合30件が完全一致した。ファイルにはSonyによる変更の記載がある。ただしファイルをプロセスが開く瞬間までは追跡していない。従来形式の`audio_policy_configuration.xml`も存在するが、存在だけで現在の設定元とは判断しない。列挙した範囲に`audio_output_policy.conf`はなかった。

### 表記の読み方

- 周波数は原則kHz。列挙値の間の周波数まで対応するという意味ではない。
- `DEFAULT`や「動的／未確定」は0Hz・未対応を意味しない。機器の接続や照会により具体値が展開される。
- PCM24 packedは3バイトの整数PCM。Q8.24は32bit容器の固定小数点、Float32は32bit浮動小数点である。
- maskはネイティブHAL表記。出力`0x3`はstereo、出力`0x1`はmono、`0x80000003`はindex表現の2ch。入力側の位置マスクとは同じ数値体系として扱わず、表の値を保持した。
- 旗値の`DIRECT`、`BIT_PERFECT`、`DEEP_BUFFER`等は経路の属性。宣言だけでは、その経路で再生中とは判断しない。
- デバイスポートのPort IDは取得時の識別子。物理機器の恒久IDではない。全デバイスポートにPort IDが割り当てられているわけではない。

<a id="overview"></a>

## 2. 全体構成と出力先別の要点

| モジュール | 出力MixPort | 入力MixPort | 宣言デバイスポート | 動的 初回→BT接続後 | 経路定義 |
| ---------- | ----------- | ----------- | ------------------ | ------------------ | -------- |
| primary    | 16          | 8           | 36                 | 2 → 4              | 30       |
| usb        | 1           | 0           | 1                  | 0 → 0              | 1        |
| r_submix   | 1           | 1           | 2                  | 0 → 0              | 2        |
| bluetooth  | 2           | 0           | 4                  | 0 → 0              | 4        |

合計：出力20、入力9、宣言デバイスポート43、動的ポートは初回2・Bluetooth接続後4、経路定義37。複数sourceを個別のsource→sink接続に展開すると268本。宣言デバイスポートはモジュール単位の論理定義数であり、物理端子や機器の台数ではない。

| 出力先                                         | 設定上の特徴                                                                                                                                   |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 内蔵スピーカー                                 | デバイスポートはPCM16 / Float32、48kHz stereo。通常・低遅延・direct・圧縮オフロード・空間音響等の経路を持つ。                                  |
| 有線ヘッドホン / ヘッドセット / line_out       | `deep_buffer_hires96_out`（Float32 / 96kHz）、`deep_buffer_hires192_out`（Float32 / 192kHz）への接続がある。                                   |
| USB DAC / USBヘッドセット                      | `hifi_playback`、`bit_perfect_playback`が接続される。形式・レートは接続機器から動的取得。`IModule/usb`のUSB Accessoryとは区別する。            |
| LE Audio ヘッドセット / スピーカー / Broadcast | primaryに個別デバイスポート。LC3・Qualcomm独自形式を宣言。96/192kHz Float専用ポートからは接続なし。direct等の一般経路を持つ。                  |
| USB Accessory                                  | 別usbモジュールの`usb_accessory output`、PCM16 / 44.1kHz / stereo。通常のUSB DACの上限ではない。                                               |

`direct_pcm_out`はQ8.24 / PCM24 packed / PCM32 / Float32、最大384kHz、最大7.1chの一般プロファイルを持つ。ただし出力デバイスの対応範囲との組合せで決まるため、内蔵DACやBluetoothが384kHz動作することを示すものではない。

ネイティブHALのチャンネルマスクをそのまま記載する。出力`0x3`はstereo、`0x1`はmono、`0x80000003`はindex表現の2ch。Java AudioTrackのstereo値`12`と数値を混同しない。Q8.24は32bit容器の固定小数点であり、3バイトのPCM24 packedとは別形式。

<a id="usb"></a>

## 3. USB DAC・USB入力・USB Accessory

通常のUSB DAC / USBヘッドセットはprimaryモジュールの`usb_device_out` / `usb_headset`に接続する。使用候補は`hifi_playback`と`bit_perfect_playback`のほか、経路表に記載した一般出力。別のusbモジュールにあるUSB Accessoryの44.1kHz制限を、USB DAC全体の上限として扱わない。

| 項目                     | 初回のFX-D03J+ L2接続時の値・判断                                                                                                                 |
| ------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| USB出力                  | PCM16 / PCM24 packed、44.1 / 48 / 88.2 / 96 / 176.4 / 192kHz、2ch                                                                                 |
| USB入力                  | usb_micにもPCM16 / PCM24 packed、同じ列挙レート、2chを展開。実録音試験は未実施                                                                    |
| hifi_playback            | 機器由来の具体的プロファイルを展開。開いていた出力はPCM24 packed / 192kHzだが、active count=0で停止中                                             |
| bit_perfect_playback     | BIT_PERFECT属性あり。取得時はDEFAULTの動的宣言のみで、具体的PCMプロファイルは未展開                                                               |
| アプリの残存優先設定     | hifi_playback / DEFAULT / PCM16 / 44.1kHz / 2ch。active clients count=0                                                                           |
| ビットパーフェクトの判断 | 今回のFX-D03J+スナップショットから実再生のビットパーフェクト成立は確認できない。動的宣言が未展開という事実だけで、全USB機器が非対応とは判断しない |
| USB Accessory            | 別usbモジュールのusb_accessory output → USB Host Out、PCM16 / 44.1kHz / stereo                                                                    |

USBのビットパーフェクト専用MixPortの接続先は`usb_device_out`と`usb_headset`のみ。A2DP・SCO・LE Audioへの接続はない。機器の形式・サンプルレートが一致すること、専用経路が存在すること、再生中にその経路が選択されていることは、それぞれ別に確認する必要がある。

<a id="analog"></a>

## 4. イヤホン端子・内蔵スピーカー

| 対象                                        | 設定と接続関係                                                                                              |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| wired_headset / wired_headphones / line_out | Float32 / 96kHzのdeep_buffer_hires96_out、Float32 / 192kHzのdeep_buffer_hires192_outの両方に接続            |
| speaker                                     | 宣言プロファイルはPCM16 / Float32、48kHz / stereo。一般・低遅延・direct・圧縮オフロード・空間音響系から接続 |
| speaker_safe                                | PCM16 / 48kHz / stereo。通知・着信等の用途別選択にも現れる                                                  |
| earpiece                                    | PCM16 / 48kHz / mono。通話等の論理受話口                                                                    |
| 96kHz専用MixPortの全接続先                  | wired_headset、wired_headphones、line_out、bt_a2dp_out、bt_a2dp_headphones                                  |
| 192kHz専用MixPortの全接続先                 | wired_headset、wired_headphones、line_out                                                                   |

今回の初回・Bluetooth接続後の採取ではイヤホン端子の再生を実測していない。上記96/192kHzはポリシー上の対応経路。専用MixPortはUSB・内蔵スピーカー・LE Audioには接続されない。`direct_pcm_out`に最大384kHzの一般プロファイルがあっても、物理出力が同条件で動くという意味ではない。

<a id="bluetooth"></a>

## 5. Bluetoothの設定とBTR17再生時の実測

初回13:26:31 JSTはBluetooth ON。ただしA2DPの`mActiveDevice`、HFPの`mActiveDevice`、LE Audioの`mActiveAudioOutDevice`はすべてnullで、音声接続・実コーデックのネゴシエーションは未確認。BLEの非音声接続があっても音声接続とは扱わない。

### Classic A2DP

primaryのA2DPデバイスポートには次のEncoded formatsが列挙される。

`SBC, AAC, APTX, APTX_HD, LDAC, CELT, APTX_ADAPTIVE, APTX_TWSP, LC3`。

Bluetoothスタックの優先度リスト：LDAC 8001、aptX Adaptive 7001、Opus 6001、aptX HD 4001、aptX 3001、AAC 2001、SBC 1001。UNKNOWN CODEC(7)は-1。このリストの順序だけで、実際にその順で接続される、または全候補がどの相手機器でも使えるとは判断しない。

A2DPハードウェアオフロードは有効。スタックに出ているオフロード候補はaptX Adaptive / LDAC / aptX HD / aptX / AAC / SBC。初回は送信レート・ビット深度がNONE表示で、実接続値はなかった。接続後のLDAC設定は本節の実測表を参照。

別bluetoothモジュールの`BT A2DP Out/Headphones/Speaker`はPCM16、44.1/48/88.2/96kHz、stereoを宣言する。これはprimary側ハードウェアオフロードの宣言とは別なので、この表だけで「Bluetoothは16bitまで」と結論しない。

### LE Audio / Broadcast

primaryの`bt_ble_headset`と`bt_ble_speaker`のEncoded formats：`LC3`, `VX_AUDIO_FORMAT_APTX_ADAPTIVE_QLEA`, `APTX_ADAPTIVE_R4`。`bt_ble_broadcast`は`LC3`。

端末内のLE Audio JSONで実際に定義されたLC3構成は16/24/32/48kHz、7.5msまたは10msフレーム、SINK/SOURCE方向、ASEあたり1ch/2ch。コメントにある全サンプリング周波数の列挙と、実際の構成に使用された値を区別して抽出した。

Qualcomm構成にはvendor coding_format=255、company_id=10の定義もある。codec_id=429は32/48/96kHz・SINK/SOURCE、codec_id=1は48/96kHz・SINKを定義。`Aptx_LeX_V1`等の構成名があるが、これだけで特定の認証・商品名・ロスレス動作を断定しない。

| 設定ファイル                                   | 構成数 | Codec構成数 | QoS構成数 |
| ---------------------------------------------- | -----: | ----------: | --------: |
| aidl_default_audio_set_configurations.json     |    198 |          79 |        25 |
| aidl_default_audio_set_configurations_qti.json |    246 |         128 |        38 |

一般・Qualcomm・vendorのシナリオファイルにはConversational / Media / Game / VoiceAssistants / Liveがある。QualcommシナリオではMediaの先頭候補に96kHzのAptx_LeX構成がある。ファイルの存在・候補順位は実際の採用や接続成立を保証しない。各Codecの周波数・方向・チャンネル数は上記のとおり。vendor JSONの末尾カンマだけを文字列の外側で除去して解析した。

### BluetoothとUSBビットパーフェクトの関係

primaryの`bit_perfect_playback`の接続先は`usb_device_out`と`usb_headset`だけで、A2DP / SCO / LE Audioには接続されていない。このUSB専用経路の有無と、Bluetooth codecの品質・可逆性は別の項目として扱う。

### Bluetooth接続後の実測（2026-10-28 13:36:16 JST）

ユーザーがBluetooth接続した後、AudioPolicy / AudioFlinger / Bluetoothスタック / MediaSessionを再取得した。各dumpは順次取得であり、同一クロックでの厳密な同時測定ではない。接続先はFiiO BTR17。以下は接続後の取得結果。全ポート一覧の基準時刻と差分は第7〜9節に明記する。

| 層・項目               | 接続後に確認した値                                                                           |
| ---------------------- | -------------------------------------------------------------------------------------------- |
| アプリの状態           | ContinuousPlayer MediaSessionはPLAYING、error=null                                           |
| アクティブAudioTrack   | uid 10507 / session 225 / port 141、PCM16 / 44.1kHz / stereo                                 |
| 使用中の出力ポート     | primaryの`deep_buffer_out`、I/O handle 37、active count=1                                    |
| AudioFlingerミキサー   | MIXER、48kHz / Float32 / stereo、Standby=no                                                  |
| AudioFlinger→HALの形式 | 48kHz / Float32 / stereo、出力先AUDIO_DEVICE_OUT_BLUETOOTH_A2DP                              |
| Bluetooth接続          | Classic A2DP、BTR17、STATE_CONNECTED                                                         |
| 選択コーデック         | LDAC / 96kHz / STEREO、BluetoothCodecConfigのbitsPerSample=32                                |
| A2DPオフロード         | Enabled=true、native側Offload started handle=65、オフロード開始応答履歴あり                  |
| 96kHz専用出力          | `deep_buffer_hires96_out`、I/O 421、Float32 / 96kHzで開いているがactive count=0、Standby=yes |
| HFP / SCO              | HFPの接続先も選択されているが、SCO occupied=false。音楽出力はA2DP                            |
| LE Audio               | mActiveAudioOutDevice=null。現在の再生経路ではない                                           |

確認できた層の並び：

```text
ContinuousPlayerのAudioTrack：PCM16 / 44.1kHz
  → AudioFlinger deep_buffer_out：Float32 / 48kHz
  → A2DPコーデック設定：LDAC / 96kHz
  → FiiO BTR17
```

AudioTrackとミキサーのレートが異なるため、現再生では元のPCMを同一形式・同一レートで通す経路にはなっていない。LDACの96kHz表示はBluetoothコーデック側の設定であり、アプリのトラックやミキサーが96kHzであることを示さない。48kHzのHAL境界から96kHzのコーデック設定までの変換位置やDSP内部形式は、取得したdumpからは特定できない。AudioTrackの値から元ファイル自体の形式・レートは断定しない。

`bitsPerSample=32`はBluetoothCodecConfig内の値であり、無線区間が32bit PCMを無変換で転送している証拠ではない。LDACの実送信ビットレート（330/660/990kbps等）・品質モードは今回のdumpでは確定できなかった。codecSpecific1=0だけから990kbps等を決めつけない。

#### この接続での選択可能コーデック

- LDAC：44.1 / 48 / 88.2 / 96kHz、bitsPerSample=32、stereo。
- SBC：44.1kHz、16bit、mono / stereo。
- ローカル能力にはaptX Adaptive / aptX HD / aptX / AACも出るが、native側の本接続のSelectableはInvalid。ローカル能力、初回dumpの優先度リスト、相手機器と交渉した結果を区別する。
- 接続後のnative codec優先度はaptX Adaptive=9001、LDAC=5001等で、初回のサービス全体リストとは値が異なる。高い優先度の項目でもSelectableでなければこの接続で選択されたことにはならない。

#### 接続による実効ポリシーの変化

全4モジュール、出力20・入力9・経路定義37は変わらない。primaryの動的デバイス数は2→4で、`bt_sco_headset`と`bt_sco_headset_mic`が追加された。これはSCOで音楽再生中という意味ではない。

primaryの`bt_a2dp_out`には、動的なDEFAULT宣言に加え、PCM16 / 48kHz / stereo、Float32 / 48・96kHz / stereoが展開された。96kHz専用MixPortがこの接続に使用できることと、現在そのMixPortが実際に再生していることは別である。

13:38:26 JSTにAudioPolicyを再確認した時点では全出力のactive countが0になっていた。上表の実再生経路は13:36:16前後の再生中スナップショットとして記録し、再確認時点でも再生が継続していたとは扱わない。

BTR17との接続で動的展開されたSCO出力・入力は、どちらもPCM16 / 8・16kHz / mono。SCOプロファイルが展開されたことは、SCO音声を送受信していた証拠ではない。

<a id="input"></a>

## 6. 入力・録音・通話・ホットワード

primaryの全8入力MixPortとr_submixの1入力を第7節に収録した。

- 内蔵bottom/top micのデバイスポートはPCM16とPCM24 packed、8～48kHzの列挙レート。back micはPCM16。通常のprimary_in自体はPCM16なので、マイクの宣言だけから任意アプリで24bit録音できるとは断定しない。
- `mmap_no_irq_in`はPCM16 / PCM24 packed / Q8.24、8～48kHzの列挙レート。
- `usb_surround_sound_input`はPCM32 / Float32、8～192kHzの列挙レート、実効マスクは5.1相当の`0x17000c`。XMLにはINDEX_MASK_6/8の記述もあるが、今回の実効プロファイルにはそのindexマスクは列挙されなかった。
- fast_input / fast_raw_inputはPCM16、VoIP録音はPCM16 mono / 8・16・32・48kHz、voice_rxはPCM16 / 8・16・48kHz。hotword_inputはPCM16 / 16kHz。
- USB、Bluetoothマイク、FM、echo reference、proxy、IP、電話受信等の入力デバイスが定義される。全デバイス形式は第8〜9節、全入力接続は第10節に収録した。

SoundTrigger middlewareにはQualcomm Sound Trigger HALとAOSP fake STHALが見える。Qualcomm側は最大8モデル・10キーフレーズ、concurrentCapture=true。初回取得時のLoaded modelsは空。fake STHALを追加の物理マイクと解釈しない。

入力MixPortはprimaryの8件とr_submixの1件、合計9件。有線ヘッドセットマイク、USBマイク、SCOマイク、A2DP/LE側の入力、FM・IP・proxy・echo reference・telephonyも第8節の宣言ポートと第10節の経路にすべて含めた。入力プロファイルの宣言と、実際の録音開始・形式の成立は区別する。

<a id="mixports"></a>

## 7. 全MixPort：出力20・入力9

以下は初回13:26:31 JSTの全プロファイル。接続後もMixPort名と経路定義の件数は同じ。Bluetooth接続による具体的なプロファイル差分は第9節。動的プレースホルダーと具体的な形式の行は併記する。

### primary：出力（16）

| MixPort                    | フラグ                                                                                                                                       | 全形式・列挙レート・ネイティブマスク                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `low_latency_out`          | 0x0006 (AUDIO_OUTPUT_FLAG_PRIMARY / AUDIO_OUTPUT_FLAG_FAST)                                                                                  | PCM16：48 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `raw_out`                  | 0x0104 (AUDIO_OUTPUT_FLAG_FAST / AUDIO_OUTPUT_FLAG_RAW)                                                                                      | PCM16：48 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `haptics_out`              | 0x0004 (AUDIO_OUTPUT_FLAG_FAST)                                                                                                              | PCM16：48 kHz；mask 0x20000003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `deep_buffer_out`          | 0x0008 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)                                                                                                       | Float32：48 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `deep_buffer_hires96_out`  | 0x0008 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)                                                                                                       | Float32：96 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `deep_buffer_hires192_out` | 0x0008 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)                                                                                                       | Float32：192 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `mmap_no_irq_out`          | 0x4001 (AUDIO_OUTPUT_FLAG_DIRECT / AUDIO_OUTPUT_FLAG_MMAP_NOIRQ)                                                                             | PCM16：48 kHz；mask 0x0003<br>Q8.24（32bit容器）：48 kHz；mask 0x0003<br>PCM24 packed：48 kHz；mask 0x0003<br>PCM32：48 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `hifi_playback`            | 0x0000 (AUDIO_OUTPUT_FLAG_NONE)                                                                                                              | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定<br>PCM16：動的／未確定 kHz；mask 動的／未確定<br>PCM16：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x0003, 0x80000003<br>PCM24 packed：動的／未確定 kHz；mask 動的／未確定<br>PCM24 packed：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x0003, 0x80000003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `bit_perfect_playback`     | 0x100000 (AUDIO_OUTPUT_FLAG_BIT_PERFECT)                                                                                                     | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `spatial_out`              | 0x40000 (AUDIO_OUTPUT_FLAG_SPATIALIZER)                                                                                                      | Float32：48 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `spatial_offload_out`      | 0x40000 (AUDIO_OUTPUT_FLAG_SPATIALIZER)                                                                                                      | PCM16：48 kHz；mask 0x2d63f                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `direct_pcm_out`           | 0x0001 (AUDIO_OUTPUT_FLAG_DIRECT)                                                                                                            | Q8.24（32bit容器）：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192, 352.8, 384 kHz；mask 0x0001, 0x0003, 0x000b, 0x0033, 0x0037, 0x003f, 0x013f, 0x063f<br>PCM24 packed：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192, 352.8, 384 kHz；mask 0x0001, 0x0003, 0x000b, 0x0033, 0x0037, 0x003f, 0x013f, 0x063f<br>PCM32：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192, 352.8, 384 kHz；mask 0x0001, 0x0003, 0x000b, 0x0033, 0x0037, 0x003f, 0x013f, 0x063f<br>Float32：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192, 352.8, 384 kHz；mask 0x0001, 0x0003, 0x000b, 0x0033, 0x0037, 0x003f, 0x013f, 0x063f                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `compress_offload_out`     | 0x20031 (AUDIO_OUTPUT_FLAG_DIRECT / AUDIO_OUTPUT_FLAG_COMPRESS_OFFLOAD / AUDIO_OUTPUT_FLAG_NON_BLOCKING / AUDIO_OUTPUT_FLAG_GAPLESS_OFFLOAD) | MP3：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x0001, 0x0003<br>FLAC：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192 kHz；mask 0x0001, 0x0003<br>ALAC：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192 kHz；mask 0x0001, 0x0003, 0x000b, 0x0033, 0x0037, 0x003f, 0x013f, 0x063f<br>APE：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192 kHz；mask 0x0001, 0x0003<br>AAC_LC：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96 kHz；mask 0x0001, 0x0003<br>AAC_HE_V1：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96 kHz；mask 0x0001, 0x0003<br>AAC_HE_V2：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96 kHz；mask 0x0001, 0x0003<br>VORBIS：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96, 128, 176.4, 192 kHz；mask 0x0001, 0x0003<br>WMA：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x0001, 0x0003, 0x000b, 0x0033, 0x0037, 0x003f, 0x013f, 0x063f<br>WMA_PRO：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96 kHz；mask 0x0001, 0x0003, 0x000b, 0x0033, 0x0037, 0x003f, 0x013f, 0x063f<br>AAC_ADTS_LC：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96 kHz；mask 0x0001, 0x0003<br>AAC_ADTS_HE_V1：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96 kHz；mask 0x0001, 0x0003<br>AAC_ADTS_HE_V2：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 64, 88.2, 96 kHz；mask 0x0001, 0x0003<br>OPUS：8, 12, 16, 24, 48 kHz；mask 0x0001, 0x0003 |
| `voice_tx`                 | 0x0000 (AUDIO_OUTPUT_FLAG_NONE)                                                                                                              | PCM16：8, 16, 32, 48 kHz；mask 0x0001, 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `voip_playback`            | 0x8000 (AUDIO_OUTPUT_FLAG_VOIP_RX)                                                                                                           | PCM16：8, 16, 32, 48 kHz；mask 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `in_call_music`            | 0x10000 (AUDIO_OUTPUT_FLAG_INCALL_MUSIC)                                                                                                     | PCM16：8, 16, 32, 48 kHz；mask 0x0001, 0x0003                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |

### primary：入力（8）

| MixPort                    | フラグ                                                | 全形式・列挙レート・ネイティブマスク                                                                                                                                                                                                                                               |
| -------------------------- | ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `primary_in`               | 0x0000 (AUDIO_INPUT_FLAG_NONE)                        | PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030                                                                                                                                                                                                 |
| `usb_surround_sound_input` | 0x0000 (AUDIO_INPUT_FLAG_NONE)                        | PCM32：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x17000c<br>Float32：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x17000c                                                                                         |
| `fast_input`               | 0x0001 (AUDIO_INPUT_FLAG_FAST)                        | PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x10000c, 0x17000c, 0x60000c, 0x64000c                                                                                                                                                                 |
| `fast_raw_input`           | 0x0005 (AUDIO_INPUT_FLAG_FAST / AUDIO_INPUT_FLAG_RAW) | PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x10000c, 0x17000c, 0x60000c, 0x64000c                                                                                                                                                                 |
| `voip_record`              | 0x0020 (AUDIO_INPUT_FLAG_VOIP_TX)                     | PCM16：8, 16, 32, 48 kHz；mask 0x0010                                                                                                                                                                                                                                              |
| `voice_rx`                 | 0x0000 (AUDIO_INPUT_FLAG_NONE)                        | PCM16：8, 16, 48 kHz；mask 0x000c, 0x0010                                                                                                                                                                                                                                          |
| `mmap_no_irq_in`           | 0x0010 (AUDIO_INPUT_FLAG_MMAP_NOIRQ)                  | PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030<br>Q8.24（32bit容器）：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030<br>PCM24 packed：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030 |
| `hotword_input`            | 0x0002 (AUDIO_INPUT_FLAG_HW_HOTWORD)                  | PCM16：16 kHz；mask 0x000c, 0x0010                                                                                                                                                                                                                                                 |

### usb：出力（1）

| MixPort                | フラグ                          | 全形式・列挙レート・ネイティブマスク |
| ---------------------- | ------------------------------- | ------------------------------------ |
| `usb_accessory output` | 0x0000 (AUDIO_OUTPUT_FLAG_NONE) | PCM16：44.1 kHz；mask 0x0003         |

### usb：入力（0）

定義なし。

### r_submix：出力（1）

| MixPort           | フラグ                          | 全形式・列挙レート・ネイティブマスク                     |
| ----------------- | ------------------------------- | -------------------------------------------------------- |
| `r_submix output` | 0x0000 (AUDIO_OUTPUT_FLAG_NONE) | PCM16：8, 11.025, 16, 32, 44.1, 48, 192 kHz；mask 0x0003 |

### r_submix：入力（1）

| MixPort          | フラグ                         | 全形式・列挙レート・ネイティブマスク                     |
| ---------------- | ------------------------------ | -------------------------------------------------------- |
| `r_submix input` | 0x0000 (AUDIO_INPUT_FLAG_NONE) | PCM16：8, 11.025, 16, 32, 44.1, 48, 192 kHz；mask 0x000c |

### bluetooth：出力（2）

| MixPort              | フラグ                          | 全形式・列挙レート・ネイティブマスク                         |
| -------------------- | ------------------------------- | ------------------------------------------------------------ |
| `a2dp output`        | 0x0000 (AUDIO_OUTPUT_FLAG_NONE) | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定 |
| `hearing aid output` | 0x0000 (AUDIO_OUTPUT_FLAG_NONE) | PCM16：16, 24 kHz；mask 0x0003                               |

### bluetooth：入力（0）

定義なし。

### 各MixPortの上限と取得時カウント

maxOpenCount / maxActiveCountは宣言上限、curOpenCount / curActiveCountは初回取得時の値。mixerBehaviorsはダンプの生の値を保持している。下表のカウントはBluetooth再生中の値ではない。

| モジュール | 方向 | MixPort                  | 上限・初回状態                                                                               |
| ---------- | ---- | ------------------------ | -------------------------------------------------------------------------------------------- |
| primary    | 出力 | low_latency_out          | maxOpenCount: 2; curOpenCount: 1; maxActiveCount: 2; curActiveCount: 0                       |
| primary    | 出力 | raw_out                  | maxOpenCount: 1; curOpenCount: 1; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 出力 | haptics_out              | maxOpenCount: 1; curOpenCount: 1; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 出力 | deep_buffer_out          | maxOpenCount: 1; curOpenCount: 1; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 出力 | deep_buffer_hires96_out  | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 出力 | deep_buffer_hires192_out | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 出力 | mmap_no_irq_out          | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 出力 | hifi_playback            | maxOpenCount: 1; curOpenCount: 1; maxActiveCount: 1; curActiveCount: 0; mixerBehaviors: 0    |
| primary    | 出力 | bit_perfect_playback     | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0; mixerBehaviors: 0, 1 |
| primary    | 出力 | spatial_out              |                                                                                              |
| primary    | 出力 | spatial_offload_out      |                                                                                              |
| primary    | 出力 | direct_pcm_out           |                                                                                              |
| primary    | 出力 | compress_offload_out     |                                                                                              |
| primary    | 出力 | voice_tx                 |                                                                                              |
| primary    | 出力 | voip_playback            |                                                                                              |
| primary    | 出力 | in_call_music            |                                                                                              |
| primary    | 入力 | primary_in               | maxOpenCount: 2; curOpenCount: 0; maxActiveCount: 2; curActiveCount: 0                       |
| primary    | 入力 | usb_surround_sound_input | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 入力 | fast_input               | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 入力 | fast_raw_input           | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 入力 | voip_record              | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 入力 | voice_rx                 | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 入力 | mmap_no_irq_in           | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| primary    | 入力 | hotword_input            | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| usb        | 出力 | usb_accessory output     | maxOpenCount: 0; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |
| r_submix   | 出力 | r_submix output          | maxOpenCount: 10; curOpenCount: 0; maxActiveCount: 10; curActiveCount: 0                     |
| r_submix   | 入力 | r_submix input           | maxOpenCount: 10; curOpenCount: 0; maxActiveCount: 10; curActiveCount: 0                     |
| bluetooth  | 出力 | a2dp output              | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0; mixerBehaviors: 0    |
| bluetooth  | 出力 | hearing aid output       | maxOpenCount: 1; curOpenCount: 0; maxActiveCount: 1; curActiveCount: 0                       |

<a id="devices"></a>

## 8. 全宣言デバイスポート：43

全4モジュールの宣言を、未接続・動的未確定のポートも含めて収録した。Port IDは初回ダンプの値。動的な実機の具体値は第9節に別掲する。

### primary（36ポート）

| 宣言デバイスポート    | Port ID | ネイティブデバイス型                             | 全形式・レート・マスク                                                                                                                                                          | Encoded formats                                                                                                                                                                         |
| --------------------- | ------- | ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `earpiece`            | 2       | {AUDIO_DEVICE_OUT_EARPIECE, @:}                  | PCM16：48 kHz；mask 0x0001                                                                                                                                                      | 記載なし                                                                                                                                                                                |
| `speaker`             | 3       | {AUDIO_DEVICE_OUT_SPEAKER, @:}                   | PCM16：48 kHz；mask 0x0003<br>Float32：48 kHz；mask 0x0003                                                                                                                      | 記載なし                                                                                                                                                                                |
| `wired_headset`       | —       | {AUDIO_DEVICE_OUT_WIRED_HEADSET, @:}             | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `wired_headphones`    | —       | {AUDIO_DEVICE_OUT_WIRED_HEADPHONE, @:}           | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `bt_sco_out`          | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_SCO, @:}             | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `bt_sco_headset`      | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET, @:}     | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `bt_sco_car_kit`      | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_SCO_CARKIT, @:}      | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `bt_a2dp_out`         | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, @:}            | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_SBC, AUDIO_FORMAT_AAC, AUDIO_FORMAT_APTX, AUDIO_FORMAT_APTX_HD, AUDIO_FORMAT_LDAC, AUDIO_FORMAT_CELT, AUDIO_FORMAT_APTX_ADAPTIVE, AUDIO_FORMAT_APTX_TWSP, AUDIO_FORMAT_LC3 |
| `bt_a2dp_headphones`  | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES, @:} | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_SBC, AUDIO_FORMAT_AAC, AUDIO_FORMAT_APTX, AUDIO_FORMAT_APTX_HD, AUDIO_FORMAT_LDAC, AUDIO_FORMAT_CELT, AUDIO_FORMAT_APTX_ADAPTIVE, AUDIO_FORMAT_APTX_TWSP, AUDIO_FORMAT_LC3 |
| `bt_a2dp_speaker`     | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER, @:}    | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_SBC, AUDIO_FORMAT_AAC, AUDIO_FORMAT_APTX, AUDIO_FORMAT_APTX_HD, AUDIO_FORMAT_LDAC, AUDIO_FORMAT_CELT, AUDIO_FORMAT_APTX_ADAPTIVE, AUDIO_FORMAT_APTX_TWSP, AUDIO_FORMAT_LC3 |
| `hdmi_out`            | —       | {AUDIO_DEVICE_OUT_HDMI, @:}                      | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `usb_device_out`      | —       | {AUDIO_DEVICE_OUT_USB_DEVICE, @:}                | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `telephony_tx`        | 18      | {AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}              | PCM16：8, 16 kHz；mask 0x0001, 0x0003                                                                                                                                           | 記載なし                                                                                                                                                                                |
| `line_out`            | —       | {AUDIO_DEVICE_OUT_LINE, @:}                      | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `fm_out`              | 4       | {AUDIO_DEVICE_OUT_FM, @:}                        | PCM16：48 kHz；mask 0x0001, 0x0003                                                                                                                                              | 記載なし                                                                                                                                                                                |
| `speaker_safe`        | 5       | {AUDIO_DEVICE_OUT_SPEAKER_SAFE, @:}              | PCM16：48 kHz；mask 0x0003                                                                                                                                                      | 記載なし                                                                                                                                                                                |
| `ip_out`              | —       | {AUDIO_DEVICE_OUT_IP, @:}                        | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `proxy_out`           | —       | {AUDIO_DEVICE_OUT_PROXY, @:}                     | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `usb_headset`         | —       | {AUDIO_DEVICE_OUT_USB_HEADSET, @:}               | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `bt_ble_headset`      | —       | {AUDIO_DEVICE_OUT_BLE_HEADSET, @:}               | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_LC3, VX_AUDIO_FORMAT_APTX_ADAPTIVE_QLEA, AUDIO_FORMAT_APTX_ADAPTIVE_R4                                                                                                     |
| `bt_ble_speaker`      | —       | {AUDIO_DEVICE_OUT_BLE_SPEAKER, @:}               | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_LC3, VX_AUDIO_FORMAT_APTX_ADAPTIVE_QLEA, AUDIO_FORMAT_APTX_ADAPTIVE_R4                                                                                                     |
| `bt_ble_broadcast`    | —       | {AUDIO_DEVICE_OUT_BLE_BROADCAST, @:}             | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_LC3                                                                                                                                                                        |
| `built_in_bottom_mic` | 25      | {AUDIO_DEVICE_IN_BUILTIN_MIC, @:bottom}          | PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030<br>PCM24 packed：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030 | 記載なし                                                                                                                                                                                |
| `built_in_top_mic`    | 26      | {AUDIO_DEVICE_IN_BUILTIN_MIC, @:top}             | PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030<br>PCM24 packed：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030 | 記載なし                                                                                                                                                                                |
| `bt_sco_headset_mic`  | —       | {AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET, @:}      | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `wired_headset_mic`   | —       | {AUDIO_DEVICE_IN_WIRED_HEADSET, @:}              | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `telephony_rx`        | 27      | {AUDIO_DEVICE_IN_TELEPHONY_RX, @:}               | PCM16：8, 16, 48 kHz；mask 0x0010<br>PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030                                                         | 記載なし                                                                                                                                                                                |
| `built_in_back_mic`   | 28      | {AUDIO_DEVICE_IN_BACK_MIC, @:back}               | PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030                                                                                              | 記載なし                                                                                                                                                                                |
| `usb_mic`             | —       | {AUDIO_DEVICE_IN_USB_DEVICE, @:}                 | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `fm_tuner_mic`        | 29      | {AUDIO_DEVICE_IN_FM_TUNER, @:}                   | PCM16：48 kHz；mask 0x000c, 0x0010<br>PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030                                                        | 記載なし                                                                                                                                                                                |
| `bt_a2dp_mic`         | —       | {AUDIO_DEVICE_IN_BLUETOOTH_A2DP, @:}             | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_LC3                                                                                                                                                                        |
| `ip_input_device`     | —       | {AUDIO_DEVICE_IN_IP, @:}                         | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `proxy_input_device`  | —       | {AUDIO_DEVICE_IN_PROXY, @:}                      | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `usb_headset_mic`     | —       | {AUDIO_DEVICE_IN_USB_HEADSET, @:}                | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | 記載なし                                                                                                                                                                                |
| `echo_reference_mic`  | 30      | {AUDIO_DEVICE_IN_ECHO_REFERENCE, @:}             | PCM16：48 kHz；mask 0x0010<br>PCM16：8, 11.025, 12, 16, 22.05, 24, 32, 44.1, 48 kHz；mask 0x000c, 0x0010, 0x0030                                                                | 記載なし                                                                                                                                                                                |
| `bt_le_mic`           | —       | {AUDIO_DEVICE_IN_BLE_HEADSET, @:}                | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定                                                                                                                    | AUDIO_FORMAT_LC3                                                                                                                                                                        |

### usb（1ポート）

| 宣言デバイスポート | Port ID | ネイティブデバイス型                 | 全形式・レート・マスク       | Encoded formats |
| ------------------ | ------- | ------------------------------------ | ---------------------------- | --------------- |
| `USB Host Out`     | —       | {AUDIO_DEVICE_OUT_USB_ACCESSORY, @:} | PCM16：44.1 kHz；mask 0x0003 | 記載なし        |

### r_submix（2ポート）

| 宣言デバイスポート  | Port ID | ネイティブデバイス型                  | 全形式・レート・マスク                                   | Encoded formats |
| ------------------- | ------- | ------------------------------------- | -------------------------------------------------------- | --------------- |
| `Remote Submix Out` | —       | {AUDIO_DEVICE_OUT_REMOTE_SUBMIX, @:0} | PCM16：8, 11.025, 16, 32, 44.1, 48, 192 kHz；mask 0x0003 | 記載なし        |
| `Remote Submix In`  | 37      | {AUDIO_DEVICE_IN_REMOTE_SUBMIX, @:0}  | PCM16：8, 11.025, 16, 32, 44.1, 48, 192 kHz；mask 0x000c | 記載なし        |

### bluetooth（4ポート）

| 宣言デバイスポート   | Port ID | ネイティブデバイス型                             | 全形式・レート・マスク                                       | Encoded formats |
| -------------------- | ------- | ------------------------------------------------ | ------------------------------------------------------------ | --------------- |
| `BT A2DP Out`        | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, @:}            | PCM16：44.1, 48, 88.2, 96 kHz；mask 0x0003                   | 記載なし        |
| `BT A2DP Headphones` | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES, @:} | PCM16：44.1, 48, 88.2, 96 kHz；mask 0x0003                   | 記載なし        |
| `BT A2DP Speaker`    | —       | {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER, @:}    | PCM16：44.1, 48, 88.2, 96 kHz；mask 0x0003                   | 記載なし        |
| `BT Hearing Aid Out` | —       | {AUDIO_DEVICE_OUT_HEARING_AID, @:}               | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定 | 記載なし        |

<a id="dynamic"></a>

## 9. 動的ポートと接続後の差分

### 初回13:26:31 JST（2ポート）

| モジュール | 動的デバイスポート | Port ID | 型・接続先                                       | 全形式・レート・マスク                                                                                                                          |
| ---------- | ------------------ | ------- | ------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| primary    | usb_device_out     | 40      | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0} | PCM16：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x0003, 0x80000003<br>PCM24 packed：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x0003, 0x80000003 |
| primary    | usb_mic            | 51      | {AUDIO_DEVICE_IN_USB_DEVICE, @:card=0;device=0}  | PCM16：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x000c, 0x80000003<br>PCM24 packed：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x000c, 0x80000003 |

### Bluetooth接続後13:36:16 JST（4ポート）

| モジュール | 動的デバイスポート | Port ID | 型・接続先                                                        | 全形式・レート・マスク                                                                                                                          |
| ---------- | ------------------ | ------- | ----------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| primary    | bt_sco_headset     | 144     | {AUDIO_DEVICE_OUT_BLUETOOTH_SCO_HEADSET, @:Bluetoothアドレス省略} | PCM16：8, 16 kHz；mask 0x0001                                                                                                                   |
| primary    | usb_device_out     | 40      | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                  | PCM16：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x0003, 0x80000003<br>PCM24 packed：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x0003, 0x80000003 |
| primary    | bt_sco_headset_mic | 151     | {AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET, @:Bluetoothアドレス省略}  | PCM16：8, 16 kHz；mask 0x0010                                                                                                                   |
| primary    | usb_mic            | 51      | {AUDIO_DEVICE_IN_USB_DEVICE, @:card=0;device=0}                   | PCM16：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x000c, 0x80000003<br>PCM24 packed：44.1, 48, 88.2, 96, 176.4, 192 kHz；mask 0x000c, 0x80000003 |

### 宣言デバイスの動的プロファイル更新

| モジュール | 種別    | ポート      | 初回                                                         | Bluetooth接続後                                                                                                                |
| ---------- | ------- | ----------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| primary    | devices | bt_a2dp_out | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定 | DEFAULT（動的／未確定）：動的／未確定 kHz；mask 動的／未確定<br>PCM16：48 kHz；mask 0x0003<br>Float32：48, 96 kHz；mask 0x0003 |

上表が両スナップショットの宣言デバイス／MixPortのプロファイル差分の全件。Bluetoothアドレスは本文から省略した。USBのcard/device表記は取得時の論理接続先である。

<a id="routes"></a>

## 10. 全経路定義：37・展開接続268

source → sinkの方向で読む。下表はsinkごとの全sourceを省略せず収録したもの。初回とBluetooth接続後で、全37定義の内容が一致した。複数sourceを列挙した行は、各sourceからそのsinkへの接続候補を意味する。経路の宣言は、同時使用や実再生の成立を保証しない。

### primary（30定義）

| 経路種別 | sink（到着先）           | sources（接続元の全件）                                                                                                                                                                                                                |
| -------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Mix      | earpiece                 | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, telephony_rx                                                                                             |
| Mix      | speaker                  | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, telephony_rx, spatial_out                                                                                |
| Mix      | speaker_safe             | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, spatial_out, telephony_rx                                                                                |
| Mix      | wired_headset            | low_latency_out, deep_buffer_out, deep_buffer_hires96_out, deep_buffer_hires192_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, spatial_out, telephony_rx                             |
| Mix      | wired_headphones         | low_latency_out, deep_buffer_out, deep_buffer_hires96_out, deep_buffer_hires192_out, direct_pcm_out, compress_offload_out, voip_playback, mmap_no_irq_out, haptics_out, spatial_out, telephony_rx                                      |
| Mix      | usb_device_out           | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, hifi_playback, telephony_rx, bit_perfect_playback, spatial_out                                           |
| Mix      | usb_headset              | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, spatial_out, hifi_playback, telephony_rx, bit_perfect_playback                                           |
| Mix      | bt_sco_out               | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, haptics_out, spatial_out, telephony_rx                                                                                                 |
| Mix      | bt_sco_headset           | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, haptics_out, spatial_out, telephony_rx                                                                                                 |
| Mix      | bt_sco_car_kit           | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, haptics_out, spatial_out, telephony_rx                                                                                                 |
| Mix      | bt_a2dp_out              | low_latency_out, deep_buffer_out, deep_buffer_hires96_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, spatial_out                                                                     |
| Mix      | bt_a2dp_headphones       | low_latency_out, deep_buffer_out, deep_buffer_hires96_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, spatial_out                                                                     |
| Mix      | bt_a2dp_speaker          | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out                                                                                                           |
| Mix      | bt_ble_speaker           | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, telephony_rx                                                                                             |
| Mix      | bt_ble_headset           | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, haptics_out, spatial_out, telephony_rx, mmap_no_irq_out                                                                                |
| Mix      | bt_ble_broadcast         | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, spatial_out                                                                                                                                                    |
| Mix      | line_out                 | low_latency_out, deep_buffer_out, deep_buffer_hires96_out, deep_buffer_hires192_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, mmap_no_irq_out, haptics_out, telephony_rx, spatial_out                             |
| Mix      | hdmi_out                 | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, voip_playback, raw_out, haptics_out                                                                                                                            |
| Mix      | proxy_out                | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, haptics_out                                                                                                                                                    |
| Mix      | ip_out                   | low_latency_out, deep_buffer_out, direct_pcm_out, compress_offload_out, haptics_out                                                                                                                                                    |
| Mix      | fm_out                   | low_latency_out                                                                                                                                                                                                                        |
| Mix      | primary_in               | built_in_bottom_mic, built_in_top_mic, built_in_back_mic, wired_headset_mic, bt_sco_headset_mic, fm_tuner_mic, telephony_rx, bt_a2dp_mic, echo_reference_mic, bt_le_mic, proxy_input_device, ip_input_device, usb_mic, usb_headset_mic |
| Mix      | fast_input               | built_in_bottom_mic, built_in_top_mic, built_in_back_mic, bt_sco_headset_mic, usb_mic, usb_headset_mic, wired_headset_mic, proxy_input_device, ip_input_device                                                                         |
| Mix      | fast_raw_input           | built_in_bottom_mic, built_in_top_mic, built_in_back_mic, bt_sco_headset_mic, usb_mic, usb_headset_mic, wired_headset_mic, proxy_input_device, ip_input_device                                                                         |
| Mix      | telephony_tx             | voice_tx, in_call_music, built_in_bottom_mic, built_in_top_mic, built_in_back_mic, wired_headset_mic, bt_sco_headset_mic, bt_a2dp_mic, echo_reference_mic, bt_le_mic, usb_mic, usb_headset_mic                                         |
| Mix      | mmap_no_irq_in           | built_in_bottom_mic, built_in_top_mic, built_in_back_mic, bt_sco_headset_mic, usb_mic, usb_headset_mic, wired_headset_mic                                                                                                              |
| Mix      | voip_record              | built_in_bottom_mic, built_in_top_mic, built_in_back_mic, wired_headset_mic, bt_sco_headset_mic, usb_mic, usb_headset_mic, bt_a2dp_mic, echo_reference_mic, bt_le_mic, ip_input_device                                                 |
| Mix      | hotword_input            | built_in_bottom_mic, built_in_top_mic, built_in_back_mic, wired_headset_mic, bt_sco_headset_mic, usb_mic, usb_headset_mic, bt_a2dp_mic, echo_reference_mic, bt_le_mic, proxy_input_device                                              |
| Mix      | usb_surround_sound_input | usb_mic, usb_headset_mic                                                                                                                                                                                                               |
| Mix      | voice_rx                 | telephony_rx                                                                                                                                                                                                                           |

### usb（1定義）

| 経路種別 | sink（到着先） | sources（接続元の全件） |
| -------- | -------------- | ----------------------- |
| Mix      | USB Host Out   | usb_accessory output    |

### r_submix（2定義）

| 経路種別 | sink（到着先）    | sources（接続元の全件） |
| -------- | ----------------- | ----------------------- |
| Mix      | Remote Submix Out | r_submix output         |
| Mix      | r_submix input    | Remote Submix In        |

### bluetooth（4定義）

| 経路種別 | sink（到着先）     | sources（接続元の全件） |
| -------- | ------------------ | ----------------------- |
| Mix      | BT A2DP Out        | a2dp output             |
| Mix      | BT A2DP Headphones | a2dp output             |
| Mix      | BT A2DP Speaker    | a2dp output             |
| Mix      | BT Hearing Aid Out | hearing aid output      |

<a id="engine"></a>

## 11. 用途別選択・音量・エフェクト

- Product Strategyは12、Volume Groupは14。初回の用途別選択先と全音量カーブを以下に収録する。
- 初回取得時のMEDIA/PHONE/NOTIFICATION等の選択先はUSB、RING/ALARM系はUSBとspeaker_safe、スピーカー強制用途は内蔵speaker、CALL_ASSISTANTはtelephony_tx。通話自体はAUDIO_MODE_NORMALで、通話動作を実測したという意味ではない。
- MMAP policyのDEFAULTはNEVER、デバイス種別ごとの明示的な項目はAUTO。AUTOの記載だけで実際にMMAP経路を利用中とは判断しない。
- SpatializerはNONE/MULTICHANNELレベル、BINAURAL/TRANSAURAL、head tracking対応の宣言がある。初回取得時はActive Track=0、実際のhead trackingはDISABLED、Stereo Spatialization=false。
- 登録済みエフェクト：Spatializer系、DAP、DSEE、360 Upmix、Dolby Music Volume Listener。初回取得時DAP/DSEE/360 UpmixはDisabled。Music Volume Listenerはセッション225でEnabledだが、Bluetooth再生中に適用されたことを示すものではない。
- `audio_policy_volumes.xml`と`default_volume_tables.xml`も保存した。実際の音量判断には実効ダンプを優先する。

### 全Product Strategy（初回・再生停止時）

| ID  | 用途別Strategy                       | 初回の選択先                                                                    | Volume Group ID / Stream                            |
| --- | ------------------------------------ | ------------------------------------------------------------------------------- | --------------------------------------------------- |
| 0   | STRATEGY_MEDIA                       | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 10 / AUDIO_STREAM_MUSIC<br>12 / AUDIO_STREAM_SYSTEM |
| 1   | STRATEGY_PHONE                       | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 13 / AUDIO_STREAM_VOICE_CALL                        |
| 2   | STRATEGY_SONIFICATION                | {AUDIO_DEVICE_OUT_USB_DEVICE, AUDIO_DEVICE_OUT_SPEAKER_SAFE, @:card=0;device=0} | 11 / AUDIO_STREAM_RING<br>9 / AUDIO_STREAM_ALARM    |
| 3   | STRATEGY_SONIFICATION_RESPECTFUL     | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 8 / AUDIO_STREAM_NOTIFICATION                       |
| 4   | STRATEGY_DTMF                        | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 6 / AUDIO_STREAM_DTMF                               |
| 5   | STRATEGY_ENFORCED_AUDIBLE            | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 7 / AUDIO_STREAM_ENFORCED_AUDIBLE                   |
| 6   | STRATEGY_TRANSMITTED_THROUGH_SPEAKER | {AUDIO_DEVICE_OUT_SPEAKER, @:}                                                  | 5 / AUDIO_STREAM_TTS                                |
| 7   | STRATEGY_ACCESSIBILITY               | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 4 / AUDIO_STREAM_ACCESSIBILITY                      |
| 8   | STRATEGY_REROUTING                   | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 2 / AUDIO_STREAM_REROUTING                          |
| 9   | STRATEGY_CALL_ASSISTANT              | {AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}                                             | 14 / AUDIO_STREAM_CALL_ASSISTANT                    |
| 10  | STRATEGY_PATCH                       | {Empty device types, @:}                                                        | 1 / AUDIO_STREAM_PATCH                              |
| 11  | STRATEGY_ASSISTANT                   | {AUDIO_DEVICE_OUT_USB_DEVICE, @:card=0;device=0}                                | 3 / AUDIO_STREAM_ASSISTANT                          |

### 全Volume Group・全デバイス分類の音量カーブ（初回）

各点は（音量インデックス, 減衰量millibel）。1 dB = 100 millibel。カーブ定義と、再生中の実音量・適用中ゲインは別の情報である。

| ID  | Volume Group                  | デバイス分類                | 全制御点（index, millibel）                               |
| --- | ----------------------------- | --------------------------- | --------------------------------------------------------- |
| 1   | AUDIO_STREAM_PATCH            | DEVICE_CATEGORY_HEADSET     | { ( 0, 0), (100, 0) }                                     |
| 1   | AUDIO_STREAM_PATCH            | DEVICE_CATEGORY_SPEAKER     | { ( 0, 0), (100, 0) }                                     |
| 1   | AUDIO_STREAM_PATCH            | DEVICE_CATEGORY_EARPIECE    | { ( 0, 0), (100, 0) }                                     |
| 1   | AUDIO_STREAM_PATCH            | DEVICE_CATEGORY_EXT_MEDIA   | { ( 0, 0), (100, 0) }                                     |
| 1   | AUDIO_STREAM_PATCH            | DEVICE_CATEGORY_HEARING_AID | { ( 0, 0), (100, 0) }                                     |
| 2   | AUDIO_STREAM_REROUTING        | DEVICE_CATEGORY_HEADSET     | { ( 0, 0), (100, 0) }                                     |
| 2   | AUDIO_STREAM_REROUTING        | DEVICE_CATEGORY_SPEAKER     | { ( 0, 0), (100, 0) }                                     |
| 2   | AUDIO_STREAM_REROUTING        | DEVICE_CATEGORY_EARPIECE    | { ( 0, 0), (100, 0) }                                     |
| 2   | AUDIO_STREAM_REROUTING        | DEVICE_CATEGORY_EXT_MEDIA   | { ( 0, 0), (100, 0) }                                     |
| 2   | AUDIO_STREAM_REROUTING        | DEVICE_CATEGORY_HEARING_AID | { ( 0, 0), (100, 0) }                                     |
| 3   | AUDIO_STREAM_ASSISTANT        | DEVICE_CATEGORY_HEADSET     | { ( 1, -5800), ( 20, -4000), ( 60, -1700), (100, 0) }     |
| 3   | AUDIO_STREAM_ASSISTANT        | DEVICE_CATEGORY_SPEAKER     | { ( 1, -5800), ( 20, -4000), ( 60, -1700), (100, 0) }     |
| 3   | AUDIO_STREAM_ASSISTANT        | DEVICE_CATEGORY_EARPIECE    | { ( 1, -5800), ( 20, -4000), ( 60, -1700), (100, 0) }     |
| 3   | AUDIO_STREAM_ASSISTANT        | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 20, -4000), ( 60, -1700), (100, 0) }     |
| 3   | AUDIO_STREAM_ASSISTANT        | DEVICE_CATEGORY_HEARING_AID | { ( 1, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 4   | AUDIO_STREAM_ACCESSIBILITY    | DEVICE_CATEGORY_HEADSET     | { ( 0, -5800), ( 26, -4200), ( 66, -1800), (100, 0) }     |
| 4   | AUDIO_STREAM_ACCESSIBILITY    | DEVICE_CATEGORY_SPEAKER     | { ( 0, -5250), ( 40, -2600), ( 60, -1730), (100, 0) }     |
| 4   | AUDIO_STREAM_ACCESSIBILITY    | DEVICE_CATEGORY_EARPIECE    | { ( 0, -5800), ( 33, -2500), ( 66, -600), (100, 0) }      |
| 4   | AUDIO_STREAM_ACCESSIBILITY    | DEVICE_CATEGORY_EXT_MEDIA   | { ( 0, -5800), ( 26, -4200), ( 66, -1800), (100, 0) }     |
| 4   | AUDIO_STREAM_ACCESSIBILITY    | DEVICE_CATEGORY_HEARING_AID | { ( 0, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 5   | AUDIO_STREAM_TTS              | DEVICE_CATEGORY_HEADSET     | { ( 0, -9600), (100, -9600) }                             |
| 5   | AUDIO_STREAM_TTS              | DEVICE_CATEGORY_SPEAKER     | { ( 0, 0), (100, 0) }                                     |
| 5   | AUDIO_STREAM_TTS              | DEVICE_CATEGORY_EARPIECE    | { ( 0, -9600), (100, -9600) }                             |
| 5   | AUDIO_STREAM_TTS              | DEVICE_CATEGORY_EXT_MEDIA   | { ( 0, -9600), (100, -9600) }                             |
| 5   | AUDIO_STREAM_TTS              | DEVICE_CATEGORY_HEARING_AID | { ( 0, -9600), (100, -9600) }                             |
| 6   | AUDIO_STREAM_DTMF             | DEVICE_CATEGORY_HEADSET     | { ( 1, -3000), ( 33, -2600), ( 66, -2200), (100, -1800) } |
| 6   | AUDIO_STREAM_DTMF             | DEVICE_CATEGORY_SPEAKER     | { ( 1, -4200), ( 42, -3600), ( 71, -3200), (100, -2800) } |
| 6   | AUDIO_STREAM_DTMF             | DEVICE_CATEGORY_EARPIECE    | { ( 1, -3400), ( 33, -2800), ( 66, -2200), (100, -1600) } |
| 6   | AUDIO_STREAM_DTMF             | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 20, -4000), ( 60, -2100), (100, -1000) } |
| 6   | AUDIO_STREAM_DTMF             | DEVICE_CATEGORY_HEARING_AID | { ( 1, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 7   | AUDIO_STREAM_ENFORCED_AUDIBLE | DEVICE_CATEGORY_HEADSET     | { ( 1, -3000), ( 33, -2600), ( 66, -2200), (100, -1800) } |
| 7   | AUDIO_STREAM_ENFORCED_AUDIBLE | DEVICE_CATEGORY_SPEAKER     | { ( 1, -2800), ( 33, -2200), ( 66, -1600), (100, -1000) } |
| 7   | AUDIO_STREAM_ENFORCED_AUDIBLE | DEVICE_CATEGORY_EARPIECE    | { ( 1, -5550), ( 33, -3950), ( 66, -2300), (100, -600) }  |
| 7   | AUDIO_STREAM_ENFORCED_AUDIBLE | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 20, -4000), ( 60, -2100), (100, -1000) } |
| 7   | AUDIO_STREAM_ENFORCED_AUDIBLE | DEVICE_CATEGORY_HEARING_AID | { ( 1, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 8   | AUDIO_STREAM_NOTIFICATION     | DEVICE_CATEGORY_HEADSET     | { ( 1, -4950), ( 33, -3350), ( 66, -1700), (100, 0) }     |
| 8   | AUDIO_STREAM_NOTIFICATION     | DEVICE_CATEGORY_SPEAKER     | { ( 1, -4200), ( 42, -2800), ( 71, -1800), (100, -800) }  |
| 8   | AUDIO_STREAM_NOTIFICATION     | DEVICE_CATEGORY_EARPIECE    | { ( 1, -5550), ( 33, -3950), ( 66, -2300), (100, -600) }  |
| 8   | AUDIO_STREAM_NOTIFICATION     | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 20, -4000), ( 60, -2100), (100, -1000) } |
| 8   | AUDIO_STREAM_NOTIFICATION     | DEVICE_CATEGORY_HEARING_AID | { ( 1, -4950), ( 33, -3350), ( 66, -1700), (100, 0) }     |
| 9   | AUDIO_STREAM_ALARM            | DEVICE_CATEGORY_HEADSET     | { ( 0, -4950), ( 33, -3350), ( 66, -1700), (100, 0) }     |
| 9   | AUDIO_STREAM_ALARM            | DEVICE_CATEGORY_SPEAKER     | { ( 0, -4200), ( 72, -1200), ( 85, -600), (100, 0) }      |
| 9   | AUDIO_STREAM_ALARM            | DEVICE_CATEGORY_EARPIECE    | { ( 0, -5550), ( 33, -3950), ( 66, -2300), (100, -600) }  |
| 9   | AUDIO_STREAM_ALARM            | DEVICE_CATEGORY_EXT_MEDIA   | { ( 0, -5800), ( 20, -4000), ( 60, -2100), (100, -1000) } |
| 9   | AUDIO_STREAM_ALARM            | DEVICE_CATEGORY_HEARING_AID | { ( 0, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 10  | AUDIO_STREAM_MUSIC            | DEVICE_CATEGORY_HEADSET     | { ( 1, -5800), ( 26, -4200), ( 66, -1800), (100, 0) }     |
| 10  | AUDIO_STREAM_MUSIC            | DEVICE_CATEGORY_SPEAKER     | { ( 1, -6920), ( 23, -4300), ( 66, -1100), (100, 0) }     |
| 10  | AUDIO_STREAM_MUSIC            | DEVICE_CATEGORY_EARPIECE    | { ( 1, -5550), ( 33, -3950), ( 66, -2300), (100, -600) }  |
| 10  | AUDIO_STREAM_MUSIC            | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 26, -4200), ( 66, -1800), (100, 0) }     |
| 10  | AUDIO_STREAM_MUSIC            | DEVICE_CATEGORY_HEARING_AID | { ( 1, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 11  | AUDIO_STREAM_RING             | DEVICE_CATEGORY_HEADSET     | { ( 1, -4950), ( 33, -3350), ( 66, -1700), (100, 0) }     |
| 11  | AUDIO_STREAM_RING             | DEVICE_CATEGORY_SPEAKER     | { ( 1, -4200), ( 72, -1200), ( 85, -600), (100, 0) }      |
| 11  | AUDIO_STREAM_RING             | DEVICE_CATEGORY_EARPIECE    | { ( 1, -5550), ( 33, -3950), ( 66, -2300), (100, -600) }  |
| 11  | AUDIO_STREAM_RING             | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 20, -4000), ( 60, -2100), (100, -1000) } |
| 11  | AUDIO_STREAM_RING             | DEVICE_CATEGORY_HEARING_AID | { ( 1, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 12  | AUDIO_STREAM_SYSTEM           | DEVICE_CATEGORY_HEADSET     | { ( 1, -1800), ( 33, -1400), ( 66, -1000), (100, -600) }  |
| 12  | AUDIO_STREAM_SYSTEM           | DEVICE_CATEGORY_SPEAKER     | { ( 1, -2600), ( 33, -2000), ( 66, -1400), (100, -800) }  |
| 12  | AUDIO_STREAM_SYSTEM           | DEVICE_CATEGORY_EARPIECE    | { ( 1, -3400), ( 33, -2800), ( 66, -2200), (100, -1600) } |
| 12  | AUDIO_STREAM_SYSTEM           | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 20, -4000), ( 60, -2100), (100, -1000) } |
| 12  | AUDIO_STREAM_SYSTEM           | DEVICE_CATEGORY_HEARING_AID | { ( 1, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 13  | AUDIO_STREAM_VOICE_CALL       | DEVICE_CATEGORY_HEADSET     | { ( 0, -3000), ( 33, -2000), ( 66, -1000), (100, 0) }     |
| 13  | AUDIO_STREAM_VOICE_CALL       | DEVICE_CATEGORY_SPEAKER     | { ( 0, -2400), ( 33, -1600), ( 66, -800), (100, 0) }      |
| 13  | AUDIO_STREAM_VOICE_CALL       | DEVICE_CATEGORY_EARPIECE    | { ( 0, -3000), ( 33, -2000), ( 66, -1000), (100, 0) }     |
| 13  | AUDIO_STREAM_VOICE_CALL       | DEVICE_CATEGORY_EXT_MEDIA   | { ( 1, -5800), ( 26, -4200), ( 66, -1800), (100, 0) }     |
| 13  | AUDIO_STREAM_VOICE_CALL       | DEVICE_CATEGORY_HEARING_AID | { ( 1, -12700), ( 20, -8000), ( 60, -4000), (100, 0) }    |
| 14  | AUDIO_STREAM_CALL_ASSISTANT   | DEVICE_CATEGORY_HEADSET     | { ( 0, 0), (100, 0) }                                     |
| 14  | AUDIO_STREAM_CALL_ASSISTANT   | DEVICE_CATEGORY_SPEAKER     | { ( 0, 0), (100, 0) }                                     |
| 14  | AUDIO_STREAM_CALL_ASSISTANT   | DEVICE_CATEGORY_EARPIECE    | { ( 0, 0), (100, 0) }                                     |
| 14  | AUDIO_STREAM_CALL_ASSISTANT   | DEVICE_CATEGORY_EXT_MEDIA   | { ( 0, 0), (100, 0) }                                     |
| 14  | AUDIO_STREAM_CALL_ASSISTANT   | DEVICE_CATEGORY_HEARING_AID | { ( 0, 0), (100, 0) }                                     |

<a id="limits"></a>

## 12. 資料の根拠と未確認範囲

本書は2026年10月28日の取得結果を独立して読める形に整理した記録である。以後のOS更新、機器接続、コーデック設定、再生ファイル変更によって状態や選択経路は変わり得る。

| 根拠                                                    | 本書への利用                                                                                |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| dumpsys media.audio_policy                              | Config source、全モジュール・MixPort・デバイスポート・経路・用途別選択・音量・active client |
| dumpsys media.audio_flinger                             | 実ミキサー名、I/O handle、レート、HAL/Processing形式、Standby                               |
| dumpsys audio / media_session                           | フレームワーク接続状態、ContinuousPlayerのPLAYING状態                                       |
| dumpsys bluetooth_manager                               | A2DP/LE/HFP状態、選択codec、選択可能codec、ローカル能力、オフロード状態                     |
| service list / VINTF                                    | AIDLサービス登録とHALバージョン宣言                                                         |
| audio_module_config_primary.xml                         | primary MixPort名24件と30経路の接続集合を実効ダンプと照合                                   |
| vendor_audio_interfaces.xml / audiohalservice_qti.rc    | Qualcommサービス構成と起動設定。audiohalservice.qtiと関連ライブラリの宣言                   |
| 従来形式audio_policy_configuration.xmlと各モジュールXML | 端末内に存在する設定資料。存在のみで実行時の設定元とは断定しない                            |
| LE Audioの5つのJSON                                     | 一般・Qualcommのcodec/QoS構成、一般・Qualcomm・vendorの用途別シナリオ                       |
| audio_policy_volumes.xml / default_volume_tables.xml    | 設定ファイルも保全。表に記載した実効カーブはAudioPolicyダンプを基準とした                   |
| SoundTrigger / middlewareの状態                         | Qualcomm側モデル上限、concurrentCapture、取得時のロード状態                                 |

初回・Bluetooth接続後とも全出力20・入力9・宣言デバイス43・経路定義37をダンプ内の件数と照合した。全経路はsource単位に展開して268接続であることを確認。動的デバイス数の変化2→4と、全プロファイル差分も照合した。LE Audio JSONの末尾カンマは文字列の外側だけを除去して解析し、コメントの候補値と実際の構成値を区別した。

- Audio Core / Effect / Bluetooth AudioのAIDLサービス直接dump計7件は`FAILED_TRANSACTION`。HAL内部のDSP処理や非公開設定をすべて取得したものではない。
- 一部SELinux等のディレクトリ一覧はPermission denied。root化や保護設定の変更は行っていない。
- Bluetooth全コーデック・全周波数の再生試験、USB全機種の互換性試験、録音試験、DACのアナログ測定、FM/HDMI等の未接続機器の動作試験は実施していない。
- 取得した宣言・接続状態は、ロスレス性、実効情報量、アナログ出力品質の測定結果ではない。
- 13:36のBTR17再生中の経路と、13:38の再確認時に全出力が停止していた状態を分けて記録した。
- 端末のBluetoothアドレスや無関係なペアリング機器名は本文に転載していない。
