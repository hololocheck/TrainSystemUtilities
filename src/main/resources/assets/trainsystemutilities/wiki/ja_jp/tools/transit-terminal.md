---
title: 乗り換え案内端末
id: tools/transit-terminal
tags: [tool, block, terminal]
---

# 乗り換え案内端末

```embed:item id=trainsystemutilities:transit_terminal size=48 label=true
```

手に持って使う案内端末です。右クリックすると、画面右下に案内画面が開きます。開いている間はマウスで操作し、Escで閉じます。

![](bws:trainsystemutilities:wiki/screens/transit-terminal__top__ja_jp.png)

[[TOC]]

## 経路を検索する

![](bws:trainsystemutilities:wiki/screens/transit-terminal__top__ja_jp.png)

「トップ」で出発駅と到着駅を入力し、候補から駅を選びます。入れ替えボタンで両駅を交換できます。両方を選ぶと検索を実行できます。

結果を選ぶと、駅ごとの発着時刻、番線、列車名などを確認できます。複数の候補がある場合は候補タブで切り替えます。詳細からナビゲーションや経路詳細HUDを有効にできます。検索履歴は1件ずつ、またはまとめて削除できます。

## ほかの画面

- 時刻表：列車名で絞り込みながら列車の時刻表を確認します。

![](bws:trainsystemutilities:wiki/screens/transit-terminal__schedule__ja_jp.png)

- マップ：路線図を表示します。ドラッグで移動、ホイールで拡大縮小します。

![](bws:trainsystemutilities:wiki/screens/transit-terminal__map__ja_jp.png)

- 設定：12・24時間表記、パネル位置の調整、詳細HUDの表示を切り替えます。

![](bws:trainsystemutilities:wiki/screens/transit-terminal__settings__ja_jp.png)

レイアウト調整を有効にすると、見出し部分をドラッグしてパネルを移動できます。「位置をリセット」で初期位置に戻します。

案内には、[駅グループ](station-range-tool.md)とCreateの列車スケジュールを使い、路線記号は[管理用コンピューター](../management-computer/overview.md)から取得します。駅が見つからない場合は、駅グループの登録をご確認ください。
