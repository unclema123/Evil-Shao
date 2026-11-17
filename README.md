# PigNpc · 小猪邵珠佳（Kotlin 3D 版）

一个 Kotlin + JavaFX 3D 的桌面互动小软件：屏幕上一只**真正立体的拟人化小猪**，
拖拽可以旋转它、滚轮缩放、点它一下她会随口吐槽；你打字说话，她头顶浮出气泡回你。

不是聊天记录窗口——主体是 3D 角色，对话只以气泡形式临时出现。

## 运行

### 方式一：直接跑打包好的程序

```
dist/PigNpc/PigNpc.exe
```

双击即可，**不需要装 Java**（jpackage 已经把运行时打进 `runtime/`）。

### 方式二：从源码运行

```bash
gradle run
```

需要 JDK 21 和 Gradle 8.11+。JavaFX 依赖由 Maven Central 提供，无需单独装 SDK。

> 仓库里没有提交 gradle wrapper（本机网络无法下载 distribution），
> 所以用本机已安装的 `gradle` 命令，而不是 `./gradlew`。

## 构建

```bash
gradle installDist                 # 生成 build/install/PigNpc
jpackage --type app-image \
  --name PigNpc \
  --input build/install/PigNpc/lib \
  --main-jar PigNpc.jar \
  --main-class com.unclema123.pignpc.LauncherKt \
  --dest dist --app-version 1.0.0
```

## 语录

`src/main/resources/quotes.txt` 是内置语料；程序运行后读写的是
`%APPDATA%\PigNpc\quotes.txt`（首次运行不自动生成，点「语录」保存后才会写）。

也可以点界面右上角**「语录」**直接改，保存后立即生效。

格式：

```
[SIG]                                   # 口头禅，一行一条
谁让我这么聪明
笑死我了

[RULE]                                  # 关键词,关键词 => 回复;;回复
吃,饭,饿 => 刚才去拿了快递，然后买了饭;;因为下午的时候不饿[坏笑]

[FALL]                                  # 兜底
……
```

## 说话风格

语料来自 2026-11-14 ~ 11-16 的真实聊天记录（对方在微信里显示为 Whisper）。
核心特征是**自夸式幽默 + 小学生斗嘴**：

- 自夸：「谁让我这么聪明」「谢谢，我就是当代福尔摩斯」「我家没我得散」
- 斗嘴：「反弹。」「你猜我是傻子吗？」「你！！！」
- 惊讶：「我的天」「哎呀，我的妈呀」「我去」「窝趣」
- 冷处理：单独一条「……」
- 幻想：「建议推举我当德州市长」「比起德州市长，我更想取代特朗普」

## 结构

```
src/main/kotlin/com/unclema123/pignpc/
├── Launcher.kt       启动入口（不继承 Application，避免 classpath 下的启动报错）
├── MainApp.kt        场景、相机、灯光、交互、语录编辑对话框
├── PigModel.kt       用 Sphere/Cylinder 拼的 3D 小猪 + 待机动画/眨眼
├── SpeechBubble.kt   Canvas 画文字 → 快照成贴图 → 放进 3D 场景的气泡
└── QuotesStore.kt    语录读写 + 关键词打分回复引擎
```

## 已知限制

- 只测过 Windows + JDK 21。JavaFX 的 `-win` 分类依赖意味着换平台需要改依赖分类。
- 3D 是基本体拼的，没有外部模型文件，所以改比例只能改代码里的数值。
- 控制台会有一条 `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'`
  警告，这是 classpath 方式加载 JavaFX 的正常提示，不影响功能。