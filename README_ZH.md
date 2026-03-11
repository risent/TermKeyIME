# TermKey IME

为终端而生，也适合高频中文输入。

面向 Android 的终端键盘，支持紧凑中英文布局、自然码双拼、可编辑宏栏，以及流式语音输入。

## 功能特性

- 终端优先的按键行为：粘滞 `Ctrl` / `Alt` / `Shift`、真实控制字符、`Alt` 发送 `ESC + key`、F1-F12、方向键、PgUp/PgDn、Home/End。
- 运行时三种键盘布局：
  - `FULL`：完整终端键盘，包含功能键行和控制键。
  - `COMPACT_ZH`：中文紧凑布局，用于双拼输入。
  - `COMPACT_EN`：英文紧凑布局，保留字母、数字和常用标点。
- 独立紧凑符号模式：
  - 点击 `#+=` 进入纯符号页。
  - 点击任意符号后自动返回之前的紧凑布局。
- 自然码双拼中文输入：
  - 基于离线词库的候选查询
  - 词组和整句优先排序
  - 本地用户词频学习
  - 候选栏点选上屏
  - 中文输入时默认 `Space` 确认首候选
- 基于 Volcengine WebSocket ASR 的流式语音输入。
- 可编辑宏栏，支持持久化自定义宏。
- 紧凑模式下支持更大的触控命中区域、长按连续删除、上滑清空删除。

## 当前输入行为

### 中文模式

- 点击 `中/EN` 切换到中文模式。
- 字母键输入自然码双拼编码。
- 中文模式下候选栏常驻显示。
- 首候选会作为预编辑 composing 文本显示。
- `Space` 默认确认首候选并清空当前双拼缓冲。
- `Backspace` 优先删除双拼编码；缓冲清空后再执行普通删除。
- 当前引擎支持单字、词组和整句候选。

### 英文模式

- 英文紧凑布局保留字母、数字和常用标点。
- `FULL` 模式会恢复完整终端键盘。

### 符号模式

- 在中文或英文紧凑模式下，点击 `#+=` 进入符号模式。
- 符号模式只显示符号键，以及必要控制键，例如 `ABC`、空格、退格、回车和 `MIC`。
- 点击任意符号后会自动插入并返回之前的紧凑布局。

### 语音输入

- 点击 `MIC` 开始流式识别。
- 识别结果会实时显示在当前输入框中。
- 再次点击 `MIC` 停止并定稿。
- 需要在设置中授予麦克风权限，并配置 Volcengine 的 `APP ID`、`Access Token` 和 `Resource ID`。

## 构建

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 34
- minSdk 26

### 构建命令

```bash
./gradlew installDebug
```

如果 Gradle 报错 `Unsupported class file major version 69`，说明当前 Java 版本过高，需要切回 JDK 17。

## 使用与安装

1. 安装 debug 构建。
2. 打开 `TermKey` 应用。
3. 在 Android 键盘设置中启用 `TermKey` 输入法。
4. 将当前输入法切换为 `TermKey`。
5. 进入应用内 `Settings` 配置宏栏或语音输入。

## 设置项

当前主要设置包括：

- 显示或隐藏宏栏
- 显示或隐藏 F 键行
- 键盘高度
- 按键震动
- 按键声音
- 粘滞修饰键
- 上滑输入替代符号
- 长按输入替代符号
- 显示或隐藏语音键
- 麦克风权限申请
- Volcengine 语音配置
- 宏编辑与宏重置

## 项目结构

```text
app/src/main/java/com/termkey/ime/
├── TermKeyIMEService.kt
├── NaturalShuangpinEngine.kt
├── ChineseLexiconStore.kt
├── VolcengineVoiceInputClient.kt
├── MacroManager.kt
├── MacroEditorActivity.kt
├── SettingsActivity.kt
└── SetupActivity.kt
```

重要资源文件：

- `app/src/main/res/layout/keyboard_view.xml`：键盘布局
- `app/src/main/res/xml/preferences.xml`：设置页
- `app/src/main/assets/chinese_lexicon.db`：离线中文词库

## 说明

- 中文词库内置在 APK 中，因此安装包体积会比普通键盘更大。
- 用户词频学习结果保存在本地设备中。
- 语音输入依赖外部 Volcengine 凭据，除此之外键盘核心功能可离线工作。

## 适用应用

更适合以下终端或 SSH 应用：

- Termux
- ConnectBot
- JuiceSSH
- Blink Shell

同时也可用于普通 Android 文本输入场景，尤其适合紧凑中文输入和语音输入。

## License

MIT
