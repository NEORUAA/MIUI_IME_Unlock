# AGENTS.md

## 实机验证约束

- 当前测试机带 Root/核心破解，支持跨签名覆盖安装。更新模块时优先使用 `adb install -r`；即使遇到签名问题，也禁止未经用户明确同意卸载任何应用。
- 禁止重启“系统框架”，否则设备会重启并导致 ADB 断联。如果确实必须重启设备，先通知用户并由用户操作。
- 安装模块后，只停止并重新启动本次涉及的作用域应用/输入法进程，使新的 Xposed hook 随进程重新加载。不要为了验证输入法侧 hook 重启系统框架。
- 实机验证使用 release APK。release 包需要使用 `/Volumes/External/Code/Android/Bandbbs_Android_Client/app/build.gradle` 中配置的密钥签名；不要把密钥密码输出到日志、命令行结果或提交到本仓库。
- 不要写死设备序列号、屏幕坐标、显示 ID、DPI 或导航栏像素。先通过 ADB 查询当前设备和 UI 状态，再决定后续命令。

## 输入法与系统配置

- 模块包名：`com.xposed.wetypehook`
- 微信输入法：`com.tencent.wetype/.plugin.hld.WxHldService`
- Gboard：`com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`
- MIUI 全面屏键盘优化开关：`settings get secure enable_miui_ime_bottom_view`，验证相关功能时应确认为 `1`。
- MIUI 底栏支持属性：`ro.miui.support_miui_ime_bottom`。
- 导航栏 inset 必须从运行时 `WindowInsets` 或真实布局状态读取，不能使用在某台设备上观察到的 `52px` 等固定值。

## 推荐验证流程

1. 使用 `./gradlew :app:assembleRelease` 构建 release APK，完成签名后执行签名校验。
2. 使用 `adb install -r <apk>` 覆盖安装；禁止以安装失败为由直接卸载旧包。
3. 分别停止微信输入法与 Gboard 进程，确认全面屏键盘优化开关开启。
4. 回到桌面，点击 Dock 上方的“搜索”按钮拉起键盘。通过 `uiautomator dump` 获取当前控件边界，不要长期复用旧坐标。
5. 分别验证微信输入法和 Gboard 首次拉起时的布局。
6. 必须点击 MIUI 底栏左侧的键盘按钮，按“微信输入法 → Gboard → 微信输入法”实际切换一次；不能只用 `ime set` 代替这个生命周期测试。
7. 每一步都截图确认：
   - 键盘内容与 MIUI 底栏之间没有额外空白。
   - 键盘顶部没有因移动 padding 产生的新空白。
   - 微信输入法美化背景与真实输入法高度一致。
   - MIUI 底栏仍显示完整，未被推出屏幕或覆盖键盘。
8. 验证结束后恢复用户原来的默认输入法，并再次确认全面屏键盘优化开关仍为 `1`。

## 日志与截图

- 重点检查 `LSPosedFramework`、`IMEBottomManager` 和目标输入法进程日志。类找不到或可选方法不存在的既有日志需要与本次布局 hook 是否成功加载分开判断。
- 设备存在多个显示屏时，普通 `screencap` 可能把警告文本写进输出文件。先查询主显示屏 ID，再使用 `screencap -d <display-id> -p`。
- 排查布局时应记录 `rootView`、`inputFrame`、`bottomArea`、直接内容 View 的位置、高度、padding 和实时导航栏 inset。
- 临时诊断日志只能用于定位问题，最终提交前必须移除，并执行 `git diff --check` 和 release 构建。

