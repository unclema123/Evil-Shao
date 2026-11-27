# Warm Companion · 温情陪护

A small Android companion app: an illustrated girl in a cosy room, ten
personality traits you combine into a character, and a quote library the app
retrieves lines from — online as tone examples, offline as her actual reply.

- Repository: **[github.com/unclema123/Evil-Shao](https://github.com/unclema123/Evil-Shao)**
- Download: [`android/app-debug.apk`](https://github.com/unclema123/Evil-Shao/raw/main/android/app-debug.apk)
  (or grab it from the repo page)
- Art source: [`art2d/scene.html`](art2d/scene.html) — the illustration is SVG,
  rasterised by [`art2d/tools`](art2d/tools) into the APK assets

<a href="docs/screenshot.jpg"><img src="docs/screenshot.jpg" width="320" alt="Warm Companion 2.5 — 暖阳画风的女生"></a>

中文说明在下面的「中文」一节。

> Renamed from **Evil Shao / PigNpc**. The old persona packs are gone; the
> character is now built from traits you pick, and the visuals are 2D.

## English

### What it is

- **Warm sunlit portrait of an illustrated girl.** A pale-yellow backdrop with a
  low sun and soft bokeh; she has long wavy light-brown hair, a blue scarf and a
  yellow top, and she is smiling. She breathes, blinks, and switches between
  seven expressions (neutral / blink / happy / shy / bored / angry / surprised)
  to match what she says.
- **Two built-in faces.** 「小暖」 and 「邵珠佳」 share the same illustration; switch
  between them in Menu → 人物. Appearance and personality are separate — the
  personality still comes from the traits you tick.
  The art is authored as SVG in [`art2d/scene.html`](art2d/scene.html) and
  rasterised into `scene_bg.jpg` + `girl_*.webp`; [`art2d/tools`](art2d/tools)
  regenerates them with a headless Chrome, so every shape stays editable.
- **Ten personality traits, not preset roles.** 温柔 / 幽默 / 毒舌 / 高冷 / 粘人 /
  理性 / 直率 / 傲娇 / 元气 / 阴阳. Each trait carries a style line and its own
  quote set. Pick any combination — that is your character.
- **Compose your own.** Menu → 人物 → 新建. Choose traits, optionally write extra
  quotes and a supplementary note, name it or leave it unnamed. No fixed identity
  is baked in; only the traits you select shape the voice.
- **Fixed lines come from the quote library by retrieval.** The app scores lines
  against what you just said, feeds the closest ones to the model as tone
  examples, and offline answers with the closest line when the match is good
  enough — instead of drawing a random quote.
- **Ambient tone.** Local time (morning / noon / late night) and the scene are
  added to the prompt, so she speaks softer at 1 a.m. and lighter in the morning.
- **WeChat-style emoticons.** She may append one `[呲牙]` / `[捂脸]` / `[比心]`
  style text emoticon to a short reply — the same bracket style you type in QQ.
- **Import your own quote file.** Menu → 导入语录, pick a `.txt` with one line
  per sentence; it is merged into the current character's library.
- **Learning from real chat, not quizzes.** Turn on 本人模式: every message the
  person types is stored locally as (previous line → their line). The app
  retrieves the closest pairs and feeds them to the model as few-shot examples;
  offline it answers with the line they actually said.
- **Bring your own model.** Any OpenAI-compatible endpoint works: Aliyun 百炼
  (preset button, DeepSeek), OpenAI, OpenRouter, a local llama.cpp server…
  A keyless public fallback is also included, but it is slow and shared.
- **Private by default.** Chat logs, learned weights and character data stay in
  the app's private storage. Only the conversation you type is sent to the API
  you configured.

### Install

1. Download [`android/app-debug.apk`](https://github.com/unclema123/Evil-Shao/raw/main/android/app-debug.apk).
2. Allow install from unknown sources, install, open **Warm Companion**.
3. Optional: Menu → AI, paste a base URL / model / API key (or tap the 百炼
   preset), then chat.

Requires Android 8.0+ (API 26); developed against API 34/35.

### Build

```bash
cd android
gradle assembleDebug --offline   # JDK 21, Android SDK 35, Gradle 8.11
```

No AndroidX, no third-party libraries: plain framework views and a hand-rolled
canvas renderer. The old GLB / pig renderer (`PigRenderer.kt`, `GlbModel.kt`,
`Mesh.kt`) is still in the tree but nothing in the 2D UI uses it.

### Traits: how the style is wired

`android/app/src/main/assets/traits.json` holds the trait library:

```json
{
  "id": "wenrou", "name": "温柔",
  "style": "说话轻，先接住对方的情绪……",
  "lines": ["慢点说，我在听。", "..."]
}
```

A character is a selection of traits:

```json
{ "name": "小暖", "traits": ["wenrou", "youmo"],
  "extraLines": ["……"], "extraStyle": "", "greeting": "慢点说，我在听。" }
```

- Trait `style` strings are concatenated into the system prompt — that is the
  personality. There is no name/age/occupation unless you write it in the
  optional note.
- `lines` from all selected traits are merged into the quote library. The
  library is queried by similarity to the latest message: best matches are used
  as tone examples online, and as the offline reply when the score is high
  enough.
- Characters created in the app are stored in `files/characters.json` (user
  data, never in the APK).

### Versus the old version

- 2.5: repainted in a warm sunlit style from a reference the user supplied, and
  added a second built-in character (邵珠佳). Both faces share one sprite set.
- Removed: all persona packs derived from a real person's chat logs.
- Added: multi-character support, a character editor, per-character learned
  lines, English product name.
- Kept: streaming replies, provider failover, web search, local knowledge base.
  The 3D pig renderer is left in the tree as unused code.

### Legacy

The Kotlin/JavaFX desktop demo (`src/`, `dist/`) and the early 2D web page
(`web-2d/`) are archived and no longer maintained. They still reference the old
pig demo and are kept only for history.

## 中文

### 这是什么

一个 Android 上的「温情陪护」小工具：暖阳背景（淡黄渐变、低低的太阳、柔光斑）
加一个插画风女生——长卷发、蓝围巾、黄 T 恤、笑着；会呼吸、眨眼，七个表情（普通 /
眨眼 / 开心 / 害羞 / 无聊 / 生气 / 惊讶）随回复切换。人物和场景的美术源文件在 `art2d/`
（SVG），用无头 Chrome 渲染成 `scene_bg.jpg` + `girl_*.webp` 打进 APK，改哪里都
还是矢量。文字以气泡形式出现，不做成聊天记录窗口。

- **两个内置形象**：「小暖」和「邵珠佳」，共用同一套暖阳插画（七个表情），
  在「人物」菜单里直接切换；形象和性格是分开的，性格仍由你勾。
- **10 种性格，不预设身份**：温柔 / 幽默 / 毒舌 / 高冷 / 粘人 / 理性 / 直率 /
  傲娇 / 元气 / 阴阳。每种性格自带一段「怎么说话」和一组语录，挑几个拼起来就是
  一个角色，不绑年龄、职业、名字。
- **自己拼角色**：菜单 →「人物」→「新建」。勾性格、可选补几句自己的语录、
  补一段说明，名字可留空。提示词里的风格全部来自你勾的性格。
- **固定的话从语录库检索**：按你刚说的话给语录打分，最贴的几句作为例句给模型；
  离线时分数够高就直接说那句，而不是随机抽。
- **环境语气**：按手机本地时间调语气，深夜会自动说轻一点、短一点。
- **表情**：可以偶尔带一个 [呲牙] [捂脸] [比心] 这类微信/QQ 文字表情。
- **导入语录**：菜单 →「导入语录」，选一个一行一句的 txt，并进当前角色。
- **本人模式**（自然学习）：打开后，这个人打的每句话都会连同上一条存进手机，
  在线时作为例子塞进提示词，离线时按相似度直接用她说过的那句。不做问卷、
  不打分。
- **模型随便接**：兼容 OpenAI 接口的都能用（百炼/DeepSeek 有预设按钮），
  也自带一个免 Key 的公开兜底通道。
- **数据只在手机里**：聊天记录、学到的语料、自建人物都存在应用私有目录，
  卸载即清除；只有你发的对话会发给你配置的模型接口。

### 安装

装 [`android/app-debug.apk`](https://github.com/unclema123/Evil-Shao/raw/main/android/app-debug.apk)
即可（也可以从仓库首页点进去下载），需要 Android 8.0+。

### 和以前比

2.0 起改名 **Warm Companion / 温情陪护**，删掉了之前按真人聊天记录整理的
全部语录包。2.1 把「人物」拆成可勾选的性格（不再预设身份），2.2 换成 2D 场景，
2.3 换成矢量插画女生 + 七个表情，2.4 把人物比例调得更像真人，2.5 按参考图重画成
暖阳画风，并加入第二个内置形象「邵珠佳」。
学习改成按角色分开的本人模式语料。

### 旧版

桌面版（`src/`、`dist/`）和最早的 2D 网页版（`web-2d/`）是历史存档，不再维护。

---

开发记录、每一轮改了什么，见 [`android/README.md`](android/README.md)。
素材来源与许可见 [`NOTICE`](NOTICE)。