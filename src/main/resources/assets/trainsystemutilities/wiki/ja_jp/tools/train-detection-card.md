---
title: 列車検知カード
id: tools/train-detection-card
tags: [tool, item, detection]
---

# 列車検知カード

```embed:item id=trainsystemutilities:train_detection_card size=48 label=true
```

列車を検知する線路上の1地点を記録するカードです。SASと連携し、列車の通過に合わせてアナウンスを流すために使います。

[[TOC]]

カードを手に持ち、Createの線路を右クリックしてください。記録した座標はツールチップで確認できます。Shift＋右クリックで解除します。線路以外は登録できません。

登録後、[鉄道管理ブロック](../railway-management.md)のアナウンス設定を開き、検知カード用スロットに入れます。再生する音源や条件は、鉄道管理ブロック側で設定します。カード自体に音声や再生条件を設定する画面はありません。

{{if-mod spatialaudiosystem}}
- [アナウンス設定（SAS導入時）](../railway-management/announcement.md)
{{/if}}
