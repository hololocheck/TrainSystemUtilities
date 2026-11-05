---
title: 列車プリセットの保存
id: train-preset-tool/save
tags: [tool, preset, train]
---

# 列車プリセットの保存

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

編成をプリセットとして保存し、再配置や共有に使えます。列車プリセットツールを持ち、Alt＋ホイールで「範囲選択」モードにします。

[[TOC]]

1. 保存する列車を囲むように、一方の角を右クリックします。
2. 反対側の角を右クリックして範囲を確定します。
3. もう一度右クリックして、保存画面を開きます。
4. プリセット名を入力し、Enterまたは保存ボタンで保存します。

![](bws:trainsystemutilities:wiki/screens/train-preset-save__ja_jp.png)

Shift＋右クリックで範囲を解除します。現在のモードと指定状況は、ホットバー上の表示やツールチップで確認できます。

保存データは`<world>/trainsystemutilities/presets/<author-uuid>/<name>.tsupreset`に置かれます（`<world>`はワールドのフォルダ）。別のワールドで使うときは、ファイルをコピーするか、[Preset Place](../preset-place/overview.md)で公開して取り込みます。

選択範囲の上限は、体積で256×256×256ブロック分です。上限を超える範囲は処理しません。読み込まれていないチャンクのブロックは含まれないため、必要な部分が読み込まれた状態で保存してください。
