---
title: Train System Utilitiesについて
id: about
---

# Train System Utilitiesについて

Train System Utilities（TSU）は、Createの鉄道を拡張するModです。線路、駅、スケジュール、信号を基に、運行管理、駅の案内表示、電化、列車プリセットの保存・共有を追加します。

[[TOC]]

## できること

| 機能 | 内容 |
|---|---|
| 駅の列車案内 | 停車中の列車と次に到着する列車を表示します。 |
| 路線の管理 | 管理用コンピューターで駅・列車・時刻表を確認します。 |
| モニター連携 | 路線図や列車案内、ポスターをモニターに表示します。 |
| 路線記号 | 記号の色や形を作成し、駅に割り当てます。 |
| 連結・切り離し | スケジュールの条件で編成を連結・分離します。 |
| 電化 | 変電所と架線を通じて、列車へFEを供給します。 |
| 列車プリセット | 自作の編成を保存し、再配置やオンライン共有に使います。 |

### 駅・表示系（11）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:railway_management_block,trainsystemutilities:management_computer,trainsystemutilities:poster_management_block,trainsystemutilities:monitor,trainsystemutilities:double_monitor,trainsystemutilities:monitor_half,trainsystemutilities:double_monitor_half,trainsystemutilities:monitor_slim,trainsystemutilities:double_monitor_slim,trainsystemutilities:station_name_sign,trainsystemutilities:station_name_sign_pole links=railway-management,management-computer/overview,poster-management,-,-,-,-,-,-,structure/station-name-sign,structure/station-name-sign
```

### ツール（3）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:station_range_tool,trainsystemutilities:train_preset_tool,trainsystemutilities:transit_terminal links=tools/station-range-tool,train-preset-tool/save,tools/transit-terminal
```

### データカード（3）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:memory_card,trainsystemutilities:monitor_link_card,trainsystemutilities:train_detection_card links=tools/memory-card,tools/monitor-link-card,tools/train-detection-card
```

### 電化システム（6）

```embed:items size=32 cols=5 label=true ids=trainsystemutilities:wire_connector,trainsystemutilities:pantograph,trainsystemutilities:fe_inverter,trainsystemutilities:substation,trainsystemutilities:insulator,trainsystemutilities:power_checker links=electrification/wire-connector,electrification/pantograph,electrification/fe-inverter,electrification/substation,electrification/insulator,electrification/power-checker
```

## 一緒に使うMod {#recommended-mods}

列車の基本的な走行とスケジュールはCreateが担います。TSUは、その運行や設備を拡張します。Mantaは操作画面などの共通基盤として使われ、現行の対応版には同梱されています。別途導入が必要な前提Modは、各配布ページをご確認ください。

SASを併用すると、列車の動きに合わせた放送を設定できます。電化では、FEを供給できる設備を組み合わせます。追加機器の接続可否は、それぞれのModが提供するFE対応を確認してください。

## こんな使い方に

複数の駅や列車を管理したいとき、案内表示や設備をそろえた駅をつくりたいとき、架線のある路線を運営したいときに活用できます。

## 開発と更新

更新によって保存形式やAPIが変わる場合があります。変更点は更新履歴をご確認ください。初めての構成は、テスト用ワールドで動作を確認すると導入しやすくなります。
