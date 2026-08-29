---
title: Preset Place 概述
id: preset-place/overview
tags: [preset-place, community, online]
---

# Preset Place 概述

```embed:item id=trainsystemutilities:train_preset_tool size=48 label=true
```

TSU 的社区分享功能。发布你自己创作的列车预设，并浏览 / 下载其他用户制作的预设。

[[TOC]]

## 整体概览

```
[本地预设] ── 上传 ──> [Preset Place 服务器]
                          │
            浏览 ←────────┤
            下载 ←────────┘
[其他用户的世界] <─── 放置
```

后端：基于 BelugaExperience 的 Supabase。
认证：Minecraft 账号关联（Microsoft 账号 → JWT）。

## 各页面

| 页面 | 内容 |
|---|---|
| [预设详情](detail.md) | 单个预设的详情 + 3D 预览 + 下载 |
| [个人资料](profile.md) | 用户主页 + 公开预设 + 关注 |
| [上传](upload.md) | 发布你自己预设的对话框（支持 Markdown 说明） |
| [创作者中心](creator-center.md) | 创作者账号的数据统计 / 仪表盘 |

## 主要功能

| 功能 | 行为 |
|---|---|
| 点赞 | 给你喜欢的预设点 ♥ |
| 下载量 | 线上预设的累计下载次数 |
| 举报 | 附带理由举报不当预设 |
| 关注 | 关注某位创作者 |
| 主页图标 | 自定义 SVG 图标（[主页图标编辑器](../management-computer/overview.md#owner-face)） |

## 如何访问 {#access}

所有 Preset Place 界面都从[**列车预设工具**](../train-preset-tool/browse.md)打开。

1. **手持****列车预设工具**。
2. 用 **Alt + 鼠标滚轮**将工具切换到 **GUI 模式**（手持时，当前模式会显示在快捷栏上方）。
3. **右键**打开列车预设浏览界面。
4. 将界面顶部的模式下拉菜单切换到 **`Place`（= 公开）**。
5. **左键**列表中的某个预设卡片，打开其[详情页](detail.md)。

各界面均经由详情页到达。

- **个人资料** … 在公开模式下，点击你自己的名字 / 图标区域；或在详情页点击上传者的名字。
- **上传** … 在 `Mine`（你的）模式下，点击你自己预设上的上传图标（[上传](upload.md)）。
- **创作者中心** … 从你自己的[个人资料](profile.md)页上的"创作者中心"按钮进入。

> [!NOTE]
> 使用 Preset Place 需要 Microsoft 账号关联。首次使用时会要求认证（详见各页面）。

## 相关

- [预设详情](detail.md)
- [个人资料](profile.md)
- [上传](upload.md)
- [创作者中心](creator-center.md)
- [列车预设浏览](../train-preset-tool/browse.md)
