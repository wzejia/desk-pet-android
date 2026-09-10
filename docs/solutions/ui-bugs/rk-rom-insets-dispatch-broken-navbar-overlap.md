---
title: rk/softwinner ROM 窗口 edge-to-edge 时 insets 派发断裂——navigationBars 报 0 但三键栏照常盖内容
date: 2026-09-10
category: ui-bugs
module: app/src/main/java/com/xiaoduo/deskpet/ui/screens/ChatScreen.kt
problem_type: ui_bug
component: navigation
symptoms:
  - 应用底部导航行/内容下缘被系统三键导航栏遮挡（仅部分 rk 板子）
  - Compose navigationBarsPadding() 已写但不生效（渲染=无垫布局）
  - 模拟器/正常设备同一代码无遮挡，实机独有
root_cause: rom_insets_dispatch_broken
resolution_type: code_fix
severity: medium
tags: [window-insets, edge-to-edge, navigation-bar, rk3568, softwinner, android-11, navinsetpolicy, density-160]
---

# 现象（10.0.18.47，rk3568_s，Android 11，density=160，三键导航）

小朵聊天页 BottomNavRow（底距 24px + 96px 圆钮）下 1/3 被系统导航栏盖住、热区下半失灵。
同一 APK 在模拟器/其他板子正常。

# 第一性证据链（三件套+像素裁决）

| 证据 | 值 | 含义 |
|---|---|---|
| `dumpsys window` MainActivity mFrame | [0,0][1080,1920] 全屏 | 窗口延伸到导航栏后（enableEdgeToEdge 生效） |
| mGivenContentInsets | [0,0][0,0] | 应用声明不消费 inset |
| NavigationBar0 mFrame | [0,1864][1080,1920] | 三键栏 56px 真实存在且盖在内容上 |
| 像素扫描图标位置 | y≈1858（=无垫布局） | **WindowInsets.navigationBars 派发 0**，navigationBarsPadding 失效 |

结论：窗口被盖 + insets 派发断裂（最坏组合）——系统 TaskSnapshot contentInsets 底部 56
证明系统侧 inset 存在，但 Compose 端拿到 0。

# 修复：NavInsetPolicy（派发值与资源兜底取大）

- `effectiveBottomInset(dispatched, navigationMode, resourceBarHeight)` =
  max(派发值, 三键模式兜底)；
- 兜底判据用 `Settings.Secure navigation_mode==0`（三键）——**断了的东西不能作为自己的
  开关**，模式查询不依赖派发链；
- 兜底值读 framework `navigation_bar_height` 资源（静态事实，ROM 瞒不住）；
- 手势模式（mode=1）不兜底：手势条极矮，资源值是三键高度，垫了会顶出空白。

ChatScreen BottomNavRow 接线（navigationBarsPadding → padding(bottom = effective)）。
5 测锁定（派发正常/断裂兜底/手势不兜底/无栏归零/派发更大取派发）。装机复验三键上移
56px、净空 28px、像素复核旧遮挡区图标清零。

# 教训

1. **模拟器暴露不了 ROM insets 派发 bug**——edge-to-edge 页面在实机上必须目检底缘。
2. 排查 insets 问题先取三件套：窗口 frame（是否延伸）+ givenContentInsets（是否声明
   消费）+ 实际渲染位置（截图像素）——三态组合直接指向根因。
3. 兜底开关与兜底值都要来自独立信源（Settings navigation_mode + framework 资源），
   不依赖断裂的派发链本身。
