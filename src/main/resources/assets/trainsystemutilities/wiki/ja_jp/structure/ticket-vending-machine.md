---
title: 券売機
id: structure/ticket-vending-machine
tags: [structure, block, ticket]
---

# 券売機

```embed:item id=trainsystemutilities:ticket_vending_machine size=48 label=true
```

行き先を選んで切符を発券する設備です。高さ2ブロック分の空間を確保し、[駅グループ](../tools/station-range-tool.md)の範囲内へ設置します。所属駅は位置から判定し、出発駅として使います。

[[TOC]]

![](bws:trainsystemutilities:wiki/screens/ticket-vending-machine__main__ja_jp.png)

右クリックで画面を開き、行き先のボタンを選ぶと、切符がインベントリへ入ります。切符は無料です。行き先が多い場合はホイールでスクロールします。閉じるボタンまたはEscで終了します。

販売先は[管理用コンピューターの券売機タブ](../management-computer/tickets.md)で設定します。行き先には、同じ線路網につながった駅を表示し、自駅は除きます。券売機タブで販売先を1駅も選んでいない場合は、それらの駅をすべて表示します。券売機タブの設定はサーバー全体で共有され、すべての券売機に反映されます。駅グループの外では利用できないため、先に駅の範囲を登録してください。

```embed:item id=trainsystemutilities:ticket size=32 label=true
```

切符には出発駅と到着駅が記録され、ツールチップで区間を確認できます。

自動改札では、切符の出発駅で入場します。出場できるのは、到着駅か、購入した区間の範囲内にある途中の駅です。出場すると切符は回収されます。切符を通すと、改札は3秒間開きます。
