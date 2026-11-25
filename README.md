# Warm Companion · 温情陪护

A small Android companion app: a real 3D pig on screen, four characters with
different voices — and you can write your own character, in your own words.

中文说明在下面的「中文」一节；Android 安装包是
[`android/app-debug.apk`](android/app-debug.apk)。

> Renamed from **Evil Shao / PigNpc**. The old persona packs are gone: 2.0
> ships four fictional characters and a character editor instead.

## English

### What it is

- **3D companion, not a chat window.** A low-poly pig (OpenGL ES 2.0) sits on
  screen and talks in a speech bubble. Drag to rotate, pinch to zoom.
- **Four built-in characters**, each with its own voice, lines and offline rules:
  - 林晚 — gentle senior, listens first, suggests second
  - 阿刺 — sharp-tongued friend, roasts you, then actually helps
  - 陈默 — calm doctor, asks one question, gives one concrete step
  - 豆豆 — a fourth-grader with impeccable kid logic and terrible jokes
- **Make your own character.** Menu → 人物 → 新建. You write the name, a
  one-line premise, a free-form style description (sent verbatim to the model),
  the catchphrases, and the greeting. Style is entirely yours.
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

1. Download [`android/app-debug.apk`](android/app-debug.apk).
2. Allow install from unknown sources, install, open **Warm Companion**.
3. Optional: Menu → AI, paste a base URL / model / API key (or tap the 百炼
   preset), then chat.

Requires Android 8.0+ (API 26); developed against API 34/35.

### Build

```bash
cd android
gradle assembleDebug --offline   # JDK 21, Android SDK 35, Gradle 8.11
```

No AndroidX, no third-party libraries: plain framework views plus a hand-written
GLB renderer.

### Characters: how the style is wired

`android/app/src/main/assets/characters.json` holds the built-ins:

```json
{
  "id": "dodo", "name": "豆豆", "tagline": "四年级小学生，幽默",
  "style": "你是豆豆，小学四年级，10 岁……",
  "greeting": "你也被作业抓来啦？",
  "lines": ["因为我是小学生，所以我有理。", "..."],
  "rules": [{"keys": ["作业", "考试"], "replies": ["你写完了吗？借我抄抄。"]}]
}
```

- `style` goes into the system prompt unchanged — that is the character.
- `lines` are the catchphrases used for offline replies and as tone examples.
- `rules` give keyword-triggered answers when no model is configured.
- Characters created in the app are stored in `files/characters.json` (user
  data, never in the APK).

### Versus the old version

- Removed: all persona packs derived from a real person's chat logs.
- Added: multi-character support, a character editor, per-character learned
  lines, English product name.
- Kept: 3D pig renderer, streaming replies, provider failover, web search,
  local knowledge base.

### Legacy

The Kotlin/JavaFX desktop demo (`src/`, `dist/`) and the early 2D web page
(`web-2d/`) are archived and no longer maintained. They still reference the old
pig demo and are kept only for history.

## 中文

### 这是什么

一个 Android 上的「温情陪护」小工具：屏幕上是一只真 3D 的小猪（OpenGL ES
2.0 渲染，可拖动旋转、双指缩放），文字以气泡形式出现，不做成聊天记录窗口。

- **内置 4 个人物**：林晚（温柔学姐）、阿刺（毒舌损友）、陈默（冷静医生）、
  豆豆（四年级小学生，幽默）。每人有自己的口头禅和离线规则。
- **自己建人物**：菜单 →「人物」→「新建」。名字、一句话人设、风格（原样进
  提示词，你想让它怎么说话就怎么写）、口头禅、见面第一句，全由你定。
- **本人模式**（自然学习）：打开后，这个人打的每句话都会连同上一条存进手机，
  在线时作为例子塞进提示词，离线时按相似度直接用她说过的那句。不做问卷、
  不打分。
- **模型随便接**：兼容 OpenAI 接口的都能用（百炼/DeepSeek 有预设按钮），
  也自带一个免 Key 的公开兜底通道。
- **数据只在手机里**：聊天记录、学到的语料、自建人物都存在应用私有目录，
  卸载即清除；只有你发的对话会发给你配置的模型接口。

### 安装

装 [`android/app-debug.apk`](android/app-debug.apk) 即可，需要 Android 8.0+。

### 和以前比

2.0 起改名 **Warm Companion / 温情陪护**，删掉了之前按真人聊天记录整理的
全部语录包，换成 4 个虚构人物 + 人物编辑器；学习改成按角色分开的本人模式语料。

### 旧版

桌面版（`src/`、`dist/`）和最早的 2D 网页版（`web-2d/`）是历史存档，不再维护。

---

开发记录、每一轮改了什么，见 [`android/README.md`](android/README.md)。
素材来源与许可见 [`NOTICE`](NOTICE)。