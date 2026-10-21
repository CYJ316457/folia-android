# Folia Android (folia-android)

Folia 的原生 Android 客户端。Kotlin + Jetpack Compose 重写，不使用 WebView 套壳。

## 与 folia-major 的关系

本项目是 [folia-major](https://github.com/chthollyphile/folia-major) 的原生移植。歌词解析器、
渲染时序（`renderHints`）、主题模型与领域数据结构均逐字段/逐函数移植自该仓库的 TypeScript 实现，
以便两个客户端能读取相同的歌词负载并保持一致的动画手感。

folia-major 以 **AGPL-3.0** 授权，因此本项目同样以 **AGPL-3.0** 授权（见 `LICENSE`）。
在线音乐、歌词、封面等第三方内容的版权归对应权利人所有。

## 范围

这是**垂直切片**，不是功能对等移植。目标是先验证最难的两件事在原生侧能否达到桌面版的手感：
歌词逐字渲染，以及熄屏后的连续播放。

已实现：

- 歌词解析：LRC、增强 LRC、VTT、YRC、QRC、KRC、AWLRC、TTML（8 种格式，纯 JVM 逻辑，有单元测试）
- 歌词渲染：9 种模式（Classic 逐字辉光 / Still 静态三行 / Tilt 分段倾斜 / Partita 不等长分栏 / Claddagh 环形排布 / Cadenza 自由散落 / Fume 文章排版 + 运镜 / Cappella 聊天气泡 / Pendolo 时钟齿轮轮盘），Compose Canvas 绘制
- 播放：Media3 ExoPlayer + MediaSession 前台服务（熄屏连续播放）
- 在线音源：网易云 API 客户端（搜索 / 歌曲 URL / 歌词）
- 本地音乐库：SAF 目录导入、音频标签读取、sidecar 歌词与文件夹封面匹配、内嵌封面提取与内容寻址存储、ReplayGain（ID3 直读 + 削波保护）、`.foliaignore` 排除规则、快照字段迁移、持久化索引、增量重扫、艺术家/专辑实体模型（含合并/拆分）、本地歌单（含自动收藏列表）
- AI 主题：按当前歌曲歌词（纯音乐用标题）生成明/暗双主题，OpenAI 兼容与 Gemini 两种协议，主题清洗与 JSON 容错解析，按歌曲缓存，可切换自动生成
- 网易云登录：扫码登录（801/802/803/800 全状态机）、cookie 持久化与启动恢复、退出登录、API 地址可在应用内修改

尚未实现（刻意留到切片验证之后）：

- 其余 5 种歌词动画模式：
  - `lumiere` / `tempera` / `sonnet`：各 1.1 万行以上，依赖 Pixi.js 的粒子与后处理管线
  - `diorama`：7000 行，依赖 three.js
  - `monet`：3760 行，纯 CSS 渐变 / 遮罩 / 混合模式，无 GPU 依赖，但需要封面取色管线
- AI 主题的封面取色模式、同步、模组

## AI 主题

桌面版把歌词发到自己的 Vercel 函数，由服务端持有 `GEMINI_API_KEY` / `OPENAI_API_KEY`。
Android 没有这样的服务端，所以这里改成**设备端持有密钥**：在应用内「AI settings」里填写
endpoint、model 和 key，明文保存在 SharedPreferences（`ai_theme_settings`），不与曲库快照混在一起，
避免密钥进入备份文件。拿到根权限的设备的读取风险由用户自行承担 —— APK 内置密钥是更差的方案。

两种协议都支持：OpenAI 兼容走 `/chat/completions` + `response_format: json_object`（DeepSeek、
vLLM、Ollama 等网关都接受），Gemini 走 `:generateContent` + `responseSchema`。请求体在
`AiThemeClient` 里构造，提示词在 `AiThemePrompts` 里，与桌面版逐字一致。

模型输出一律经过 `ThemeSanitizer`：非法颜色回退、3 位十六进制展开、空白词丢弃、图标数上限 12、
缺失半边整体回退。渲染层永远拿不到未经校验的字符串。

验证用的两个本地 stub 在 `tools/` 下：

```bash
node tools/theme-stub-server.cjs     # OpenAI 兼容，:8099
node tools/gemini-stub-server.cjs    # Gemini，:8098
```

模拟器里把 endpoint 填成 `http://10.0.2.2:8099/v1/chat/completions` 即可。

## 在线登录

网易云没有公开 API，每个用户都要自建一个 NeteaseCloudMusicApiEnhanced 实例，所以地址是可运行时配置的：
应用内「NetEase account」面板底部填写 `http://<host>:<port>` 并点 Apply。改动地址会清空当前登录态，
因为 cookie 属于签发它的那个服务端。`BuildConfig.NETEASE_API_BASE` 只是首次启动的默认值。

扫码流程与桌面版一致：`/login/qr/key` → `/login/qr/create?qrimg=true` → 每 2 秒轮询 `/login/qr/check`。
返回码 801 等待、802 已扫码、803 确认（带 cookie）、800 过期；其余都按失败处理，其中 502 视为可重试
（二维码仍有效），8821 这类风控视为终止。确认后还要用 `/login/status` + `/user/account` 交叉校验
id 是否一致，因为过期 cookie 会让前者返回上一个用户的资料。

未登录时会自动取一个匿名 cookie（`/register/anonimous`），搜索和取流地址都靠它；API 若回过
301/401/403 就清掉重新取，否则会一直带着失效值请求。

验证用的桩服务器能完整走完扫码时间线：

```bash
node tools/netease-login-stub.cjs    # :8097
curl http://127.0.0.1:8097/__scan     # 模拟手机扫码
curl http://127.0.0.1:8097/__confirm  # 模拟手机确认
curl http://127.0.0.1:8097/__reset    # 回到未登录
```

## Pendolo

桌面版的 pendolo 有两条渲染路径：`pendoloClockworkWebGL.ts`（504 行）和
`pendoloClockworkDraw2d.ts`（450 行）。后者不是占位实现，而是完整的 Canvas2D 渲染器——
齿轮齿形、发条螺旋、宝石轴承、径向刻度、中心渐晕、日内瓦纹都在里面，桌面版自己在 WebGL2
不可用时就回退到它。所以这里直接移植 2D 路径，跳过 WebGL 那 1100 行（含 595 行网格数据），
视觉要素一个不少。

渲染拆成两半：`PendoloClockworkBuilder` 产出图元显示列表（纯算术，JVM 单测覆盖），
`PendoloCanvas` 只负责把图元变成绘制调用。桌面版那 450 行绘制在浏览器里没法单测，
这样拆之后，图层顺序、装饰级别、齿形闭合这些都能在测试里断言。

摆轮的低音响应在 Android 上是常量 `0.18`，与桌面版无分析器时的默认值一致。为了一个装饰性
摆动去申请 `RECORD_AUDIO`，代价和收益不成比例。

## 构建

需要 JDK 17、Android SDK（compileSdk 36）。`local.properties` 需指向 SDK：

```properties
sdk.dir=/path/to/Android/Sdk
```

```bash
./gradlew assembleDebug
./gradlew test          # 716 个单元测试，含真实 AMLL TTML 与 ffmpeg ID3 文件
```

测试里有一份从 `api.amll.dev` 抓取的真实 TTML（晴天 / 周杰伦，62 行 / 494 个音节），
用来验证解析器在真实文档上的行为，而不只是手写 fixture。

播放需要自建网易云 API 实例，在 `app/build.gradle.kts` 的 `NETEASE_API_BASE` 中配置。
