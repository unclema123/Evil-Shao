# 邪恶邵 · Android 版

屏幕上一只**真 3D** 的拟人小猪（OpenGL ES 2.0 渲染，不是贴图）。
拖动可以真的转到侧面和背面，点一下她会随口吐槽，打字说话她头顶冒气泡回你。

## 安装

```
app/build/outputs/apk/debug/app-debug.apk
```

把这个 APK 拖进雷电模拟器（或发到手机）即可安装。最低 Android 7.0（API 24）。

## 构建

```bash
gradle assembleDebug
```

需要：

- JDK 17 或 21
- Android SDK，含 `platforms;android-35` 和 `build-tools;35.0.0`
- `local.properties` 里指定 `sdk.dir`

## 雷电模拟器

```bash
# 启动模拟器
E:\leidian\LDPlayer14\ldconsole.exe launch --index 0

# 装应用
E:\leidian\LDPlayer14\ldconsole.exe adb --index 0 --command installapp --filename "app-debug.apk"

# 启动应用
E:\leidian\LDPlayer14\ldconsole.exe adb --index 0 --command launchapp --packagename com.unclema123.evilshao
```

## 实现要点

| 文件 | 作用 |
|---|---|
| `Mesh.kt` | 球体/圆柱体网格生成，position+normal 交错存放 |
| `PigRenderer.kt` | GLES20 渲染器：Phong 着色、深度测试、每帧动画 |
| `Quotes.kt` | 语录解析 + 关键词打分回复 |
| `MainActivity.kt` | 触摸拖拽旋转、气泡动画、输入框、记录面板 |
| `ChatLog.kt` | 聊天记录落盘（jsonl）与导出 |

几何体在构建时就把半径烘进顶点，运行时只做旋转和平移——这样法线矩阵等于旋转矩阵，
不需要求逆转置，也避免了非等比缩放把法线搞歪。

## 聊天记录

每次对话都会落盘，右上角「记录 N」按钮可以翻看、分享、清空。

- 存放位置：`/data/data/com.unclema123.evilshao/files/chatlog.jsonl`
- 格式：每行一条 JSON，`{"t":时间戳,"r":"我"/"她","m":"内容"}`
- 记录内容：你发的每条消息、小猪的每条回复、以及点她脑袋触发的那句吐槽
- 「分享」走系统分享面板，可以直接发到微信/QQ/邮箱

用 adb 也可以直接读出来：

```bash
adb -s 127.0.0.1:5555 shell run-as com.unclema123.evilshao cat files/chatlog.jsonl
```

> **注意**：记录只存在本机上。别人在自己的手机上装了这个 APK，他们的聊天记录你这边看不到。
> 要让多个人的记录都汇总到你这儿，需要一个后端（服务器收数据），目前没有。

## 和桌面版的关系

桌面版（Kotlin + JavaFX，仓库 `unclema123/Evil-Shao`）是另一套实现，两者共用同一份
语录语料（`assets/quotes.txt`）。Android 上 JavaFX 跑不了，所以这里是完全重写的 GLES 版本。