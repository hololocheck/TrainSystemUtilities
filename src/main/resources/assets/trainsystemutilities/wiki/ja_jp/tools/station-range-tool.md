---
title: 駅範囲指定ツール
id: tools/station-range-tool
tags: [tool, station]
---

# 駅範囲指定ツール

```embed:item id=trainsystemutilities:station_range_tool size=48 label=true
```

2点で囲んだ空間に駅名を付け、駅グループとして登録します。[駅名サイン](../structure/station-name-sign.md)、[券売機](../structure/ticket-vending-machine.md)、自動改札、[乗り換え案内](transit-terminal.md)などが、共通の駅情報を参照します。

[[TOC]]

## 駅を登録する

1. Alt＋ホイールで「範囲選択」モードにします。
2. 駅エリアの一方の角を右クリックします。
3. 反対側の角を右クリックします。
4. 右クリックで保存画面を開き、駅名を入力してEnterで保存します。

![](bws:trainsystemutilities:wiki/screens/station-group-save__ja_jp.png)

範囲にはCreateの駅ブロックを含め、体積は64×64×64ブロック以内にします。番線の採番方法は、保存前に範囲選択モードでCtrl＋ホイール押し込みを使い、「自動」「左から」「右から」で切り替えます。「左から」は座標の小さい端（西または北）、「右から」は大きい端（東または南）を1番線とします。

## 登録した駅を管理する

![](bws:trainsystemutilities:wiki/screens/station-group-manage__ja_jp.png)

「管理 GUI」モードにして右クリックすると、駅名の変更、メンバーの確認、削除を行えます。削除時には確認画面が表示されます。「閲覧 (枠表示)」モードでは、登録した駅の範囲を枠で確認できます。

![](bws:trainsystemutilities:wiki/screens/station-group-manage-delete__ja_jp.png)

管理用コマンドはありません。駅の管理は、このツールの「管理 GUI」モードから行ってください。設備の所属駅は設置位置から判定されるため、駅名サインや券売機などを使う前に、駅の範囲を登録します。
