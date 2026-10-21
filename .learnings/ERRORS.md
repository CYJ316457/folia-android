# [ERR-20261018-001] UI 自动化测试必须先 dump 取坐标，不能复用屏幕上一步的坐标

**错误现象**：验证"合并实体"功能时，合并始终不生效。加了日志才发现 `mergeEntities` 从未被调用，点击落在了错误位置。

**根因**：长按列表项弹出「合并目标选择器」浮层后，我沿用了列表视图的行坐标 `(540, 690)` 去点。但浮层遮挡后同一屏幕区域的内容完全不同，target 行实际在 `(164, 395)`。第一次点击只是关掉了浮层。

**为什么会误判**：浮层弹出前我已经用 `uiautomator dump` 解析过 bounds 取到 `(540, 513)` 这个正确坐标，于是假设后续点击也可以用类似估算位置。但浮层是新的组件树，布局和列表无关。

**修复方式**：每次点击前重新 dump，从 bounds 解析中心点：
```python
b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]', tag)
x1,y1,x2,y2 = map(int, b.groups())
center = ((x1+x2)//2, (y1+y2)//2)
```

**How to apply**：任何浮层/对话框/选择器弹出后的点击，都必须重新 dump。屏幕上出现的内容发生变化时，之前的所有坐标全部作废。这条在 Compose 里尤其明显 —— 浮层覆盖在同一个 Activity 上，`dumpsys activity` 看不出任何变化，只有 dump 元素树才能确认当前实际显示的是什么。

# [ERR-20261018-002] 全屏浮层不消费 BACK 键，按 BACK 会直接退出应用

**错误现象**：UI 自动化验证拆分功能时，实体详情页（EntitySongsSheet）打开状态下按 BACK，想关闭浮层回到列表，结果整个应用被杀回桌面。

**根因**：`EntitySongsSheet` / `MergeTargetPicker` 这类全屏 Box 浮层只挂了 `clickable(onClick = onDismiss)` 背景点击关闭，没有 `BackHandler`。Compose 里 BACK 键默认穿透到 Activity，直接 finish。

**修复方式**：验证流程中关闭浮层要**点击内容区外的空白处**（如 `(50, 100)`），不要用 BACK 键。应用侧真正的修法是给浮层加 `BackHandler(onDismiss)`。

**How to apply**：在这个项目的任何 Compose 全屏浮层上做自动化，一律用「点外部」关闭。同时这是一个待修的 UX 缺陷：真实用户按 BACK 也会退出应用而不是关浮层，应补 `BackHandler`。

# [ERR-20261018-003] "兜底分支"把未知数据标成具体类型，反而把失败推迟到无法处理的位置

**错误现象**：`sniffImageMimeType` 的两个测试（随机字节、全零字节）失败，报 `java.lang.RuntimeException: Method decodeByteArray in android.graphics.BitmapFactory not mocked`。

**根因**：我在格式探测函数末尾加了"让平台解码器兜底"的分支 —— 魔数不认识时调 `BitmapFactory.decodeByteArray` 试一次，认得出就当 JPEG。Android framework 类在纯 JVM 单元测试里是 stub，这个方法直接抛异常而不是返回 null。

**深层问题**：这个 fallback 本身设计就不对，测试失败只是恰好暴露了它。

把无法识别的字节标成 `image/jpeg` 是撒谎。后果是导入时报告成功、封面 ID 也生成并存盘，直到真正显示时才解码失败 —— 正是这个函数本来要防止的失败模式。而且"解码器认得"和"这是 JPEG"是两回事，认得出的可能是 PNG 或 WebP，标成 JPEG 只是让问题隐蔽起来。

**修复方式**：删掉 fallback，只信魔数。魔数不认识就返回 null 表示"不是受支持的图片"。

**How to apply**：任何"加个兜底分支让它更健壮"的想法，先问兜底失败时会发生什么。如果兜底把未知数据标成一个具体类型而不是报告未知，它把上游的错误检测推迟到了下游，而下游的位置通常已经无法处理了。Android framework 类（BitmapFactory、MediaMetadataRetriever、Bitmap）在 JVM 测试里都不可用，涉及它们的逻辑要么注入接口，要么设计成不依赖它们的分支。

# [ERR-20261018-004] Kotlin 属性声明顺序导致 init 块读到 null 并崩溃

**错误现象**：加入 ReplayGain 后应用启动即崩溃，`java.lang.NullPointerException: Parameter specified as non-null is null: method calculateReplayGain, parameter mode`。

**根因**：我把 `private var replayGainMode: ReplayGainMode = ReplayGainMode.TRACK` 声明在 `init { }` **之后**。Kotlin 按声明顺序初始化属性，`init` 块里启动的协程在首次发射时读这个属性，此刻它还没被赋值，于是拿到 null（绕过 Kotlin 的非空检查，因为是通过 lambda 捕获读取的）。

**修复方式**：把属性声明移到 `init` 块之前，并在注释里写明原因，防止后人"整理代码"时又挪回去。

**How to apply**：任何会被 `init` 块（或其启动的协程）读取的属性，必须声明在 `init` 之前。这类缺陷编译器不报错、单元测试也不覆盖（ViewModel 的 init 通常不在测试范围内），只在真机启动时暴露。相关地，`init` 里启动的 `collect` 会在属性初始化完成前就订阅，即使声明顺序正确，也要确认首帧读到的不是默认值。

# [ERR-20261018-005] Kotlin 块注释里的 `**/` 会提前终止注释

**错误现象**：写 `.foliaignore` 的 glob 编译器时，注释里描述 `**/` 的语义，编译报一堆 `Syntax error: Expecting a top level declaration`，全部指向注释结束后的代码。

**根因**：注释文本里出现 `` `**/` `` —— 其中的 `*/` 被 Kotlin 词法分析器当作块注释结束符。注释提前终止，剩下的中文描述变成了代码。

**修复方式**：改写措辞避免 `*/` 序列，不写反引号包住的 `**/` 字面量。

**How to apply**：在 Kotlin/Java/C 系的块注释里描述通配符或路径时，注意 `*/` 序列。常见触发场景：写 glob 语法（`**/*.mp3`）、写正则（`.*\/`）、写路径（`src/*/test`）。安全做法是用 "a double star followed by a slash" 这类文字描述，或者改用 `//` 行注释。

# [ERR-20261018-006] 增量扫描必须带元数据版本，否则新增字段永远回填不到老曲库

**错误现象**：加入 ReplayGain 提取后，在设备上验证时库里的 `replayGainTrackDb` 始终是 null，而解析器本身在真实 ffmpeg 文件上测试通过。

**根因**：这首歌之前已经导入过，文件 size 和 mtime 都没变，增量扫描判定为 unchanged，直接复用旧记录 —— 而旧记录是在 ReplayGain 提取代码存在之前生成的，没有那个字段。

这不是 bug，是增量扫描的固有性质：**按文件变更判定是否重读，无法感知"提取逻辑变了"**。

**修复方式**：引入 per-song 的 `embeddedMetadataVersion`（对齐桌面版的 `EMBEDDED_METADATA_VERSION`）。版本不匹配即强制重读。在 `diffScan` 里把 `metadataOutdated` 和 `audioChanged` 并列作为重读条件。

注意 relink 分支不能顺手把版本号写成当前值 —— 它复用的是旧标签，声称自己是新版本会让这条记录永远跳过重读。必须原样保留 `stored.embeddedMetadataVersion`。

**How to apply**：任何"按文件指纹做增量处理"的管线（导入、缩略图生成、索引构建），只要处理逻辑会演进，就必须带一个逻辑版本号。验证方式：把库里记录的版本改回 0、清空新字段，重扫后应全部恢复 —— 本项目用这个方法在模拟器上验证过。

# [ERR-20261019-001] Canvas 多段布局按字号推算间距，忽略了画布实际高度

**错误现象**：实现 `tilt` 歌词模式时，分段后的两行文字始终重叠。连续改了四次间距参数（1.5×→2.0×→2.2×→2.6×字号）都无效。

**根因**：间距是从 `fontSizeSp` 推算的，而画布高度由外层 Box 决定（240dp）。两者不一致时，推算出的总高度可能远小于实际可用高度，导致所有段都挤在画布中心附近。

关键诊断动作是加一行日志打印 `layout.segments`，发现两段都是 `tilt=false`（所以样式分支没问题），问题纯在垂直定位。

**修复方式**：按画布高度均分，而不是按字号推算：

```kotlin
val bandHeight = size.height / count
var y = bandHeight / 2f
// 每段后 y += bandHeight
```

**How to apply**：Compose Canvas 里做多元素垂直布局时，**画布高度是唯一权威**。任何从字体大小、行数或经验系数推算出的间距，都可能在容器尺寸变化时失效。先均分，再在均分的基础上做视觉微调，而不是反过来。

另外：这次连续四次"调参"都是无效修复。正确做法是第一次失败后就加日志确认实际状态（段数、tilt 标记、y 值），而不是继续猜测数值。

# [ERR-20261019-002] 环形排布的字距按字形量宽算，忽略了旋转后的包围盒

**错误现象**：实现 `claddagh` 环形歌词时，字符沿弧线挤成一团。先后调整了缩放系数、旋转 pivot、字号自适应、半径来源，共五轮都无效。

**根因**：字距太小。每个字符在弧上占 `fontSize * 1.15` 的弧长，而字形本身宽 `fontSize`，只剩 15% 的间隙。字符各自按切线角度旋转后，包围盒比正放时更宽，这点间隙立刻被吃掉。

诊断转折点是用 Python 把实际参数代入算了一遍：

```
单字 advance = 34.6px, 字号 26px
间隙 = 8.6px  → 旋转后必然贴边
```

**修复方式**：字距提到 `1.75em`。这个值来自"字形宽 + 旋转溢出 + 可读间隙"的估算，不是试出来的。

**How to apply**：沿曲线/圆弧排布文字时，字距必须显著大于 1em。正放时 1.05em 就够，旋转后需要 1.5–2em，因为每个字形的包围盒随旋转角度膨胀。这个系数应该从几何算出，而不是反复调参。

另外：这轮的五次"修复"里有四次方向全错。正确做法是**第一次失败就把实际数值算出来**（半径、角跨度、单字占弧、字形宽），一眼能看出间隙只有 8.6px。在 Canvas 布局问题里，"算一下"比"改一下再截图"快得多。

# [ERR-20261019-003] 碰撞检测救不了本身就重叠的初始位置

**错误现象**：实现 `cadenza`（歌词自由散落）时，17 个词全部挤在一起互相重叠。桌面版算法有完整的环形搜索 + 重叠面积评分，我照搬了却无效。

**根因**：初始位置错了。我用「线性分数」分布词的位置：

```kotlin
val fraction = index / (size - 1) - 0.5f
preferredX = fraction * maxWidth * 0.42f
preferredY = fraction * maxHeight * 0.34f
```

相邻两个词的初始位置只差一个步长，在 17 个词时几乎重合。环形搜索从重叠的起点出发，搜索半径又受限于画布边界，找不到空位就退化成「选出重叠最小的那个」—— 于是所有词堆在一起。

**修复方式**：改成网格分配。先按 `ceil(sqrt(n))` 划分行列，每个词独占一格，再在格内加确定性抖动：

```kotlin
val columns = ceil(sqrt(total))
val x = (index % columns - (columns - 1) / 2f) / max((columns - 1) / 2f, 1f)
val y = (index / columns - (rows - 1) / 2f) / max((rows - 1) / 2f, 1f)
```

**How to apply**：碰撞检测是**补救机制**，不是布局策略。它只能解决「大多数位置已合理、个别需要微调」的情况；如果初始分布本身就密集，搜索会在有限的搜索半径内穷尽所有候选仍找不到空位，然后退化为最差结果。

设计自由布局的顺序应该是：先给出保证不重叠的粗分配（网格/行列/分层），再用抖动和碰撞检测做视觉优化。反过来先随机再避让，在元素数量超过阈值后必然失败。

这个教训和 [ERR-20261019-001]（Canvas 多段布局按字号推算间距）属于同一类：**布局问题的第一性原理是可用空间如何划分，而不是事后如何补救**。

# [ERR-20261019-004] 歌单的悬空 songId 必须在读取时修复，不能只在写入时清理

**背景**：实现本地歌单时，一个设计选择值得记录。

**问题**：歌单存的是 `songIds: List<String>`。歌曲可以从曲库消失 —— 文件被删、文件夹被移除、`.foliaignore` 排除了它 —— 而这些路径都不会经过歌单的写入逻辑。如果只在「从歌单删除歌曲」时清理，歌单里会留下指向不存在歌曲的 id，渲染时变成空白行。

**桌面版的做法**：`getLocalPlaylists()` 每次读取都拿当前曲库的 `validSongById` 过滤一遍，而不是在写入时维护一致性。

**我照搬了**，并在 `LocalPlaylistOps.repair(playlist, liveSongIds)` 里实现。关键细节：**没有变化时返回同一个实例**，调用方可以用引用比较跳过写入 —— 否则每次读取都会触发一次无意义的磁盘写。

**How to apply**：任何「A 存了 B 的 id」的跨表引用，只要 B 可能通过 A 之外的路径被删除，就必须在**读取时**做存在性过滤，而不是指望写入时保持一致性。这个模式的通用形式是：把引用完整性当成读取时的投影，而不是写入时的约束。

对应地，删除操作应该「软」——歌单不需要知道歌曲是否消失，它只是一个 id 列表；把「这个 id 现在是否有意义」的判断留给读取方。这样删除歌曲的代码完全不用关心有多少歌单引用了它。

# [ERR-20261020-001] Android 的 ICU 正则不接受裸的 `}`，而 JVM 接受

**错误现象**：AI 主题功能在单元测试里 37 个用例全绿，装到模拟器上点「生成主题」立刻闪退：

```
java.lang.ExceptionInInitializerError
Caused by: java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 9
^\s*\{.*}\s*$
         ^
	at top.izuna.foliamajor.lyrics.PureMusicDetector.<clinit>(PureMusicDetector.kt:26)
```

**根因**：Android 用的是 ICU 正则引擎，`Pattern.compile` 对孤立的 `}` 报错；JVM 的 `java.util.regex` 宽容地把它当字面量。这段 pattern 是直接从桌面版 `pureMusic.ts` 抄过来的（JS 的 `RegExp` 同样宽容），所以两端行为一致、测试也一致，只有真机上炸。

失败位置是 `object` 的静态初始化块，因此只要第一次触达 `PureMusicDetector` 就抛 `ExceptionInInitializerError` —— 表现为「点按钮就闪退」，而不是主题生成失败，很容易误判成网络或序列化问题。

**修复方式**：把 `}` 转义成 `\}`：

```kotlin
private val JSON_LINE_PATTERN = Regex("^\s*\{.*\}\s*$")
```

**How to apply**：从 JS/TS 移植正则时必须逐条检查这几种写法，它们在 JS 里合法、在 ICU 里非法：

| 写法 | JS | ICU (Android) | 处理 |
|---|---|---|---|
| 裸 `}` | ok | **报错** | 写成 `\}` |
| `[\w]` | ok | 有警告但可用 | 保持 |
| `(?<name>…)` | 需 ES2018 | ok | 保持 |
| 孤立的 `{` | 报错 | 报错 | 加量词或转义 |

更一般地说：**纯 JVM 单测通过不代表正则能在设备上编译**。凡是新增从桌面版移植的正则，都要在真机/模拟器上至少跑一次初始化路径。这类错误无法用「多写测试」发现，因为没有 Robolectric 时跑的就是 JVM 引擎；唯一的检测手段是设备验证。

另一个放大器：把正则放在 `object` 的顶层 `val` 里，会让失败变成类初始化错误（`ExceptionInInitializerError`），堆栈只指向 `<clinit>`，看不出是哪条正则。如果排查困难，可以改成 `by lazy` 让失败发生在使用点。

# [ERR-20261020-002] Coil 2 不支持 data URI，而且失败时不报错

**错误现象**：扫码登录面板里，二维码区域是一个纯白方块。没有异常、没有日志，`AsyncImage` 就是不显示。

**根因**：网易云 API 的 `/login/qr/create?qrimg=true` 返回的是 `data:image/png;base64,…`。Coil 2.x 没有
data-URI fetcher（data URI 支持是 Coil 3 才引入的），传入这种字符串时它既找不到 loader，也不会抛出
可见的异常或打印 warn —— 表现就是「什么都没有」。

**修复方式**：在进入 UI 之前解码：

```kotlin
object DataUrl {
    private const val BASE64_MARKER = ";base64,"
    fun decodeBase64Image(dataUrl: String?): ByteArray? {
        val value = dataUrl?.trim() ?: return null
        if (!value.startsWith("data:", ignoreCase = true)) return null
        val i = value.indexOf(BASE64_MARKER, ignoreCase = true)
        if (i < 0) return null
        return runCatching { Base64.decode(value.substring(i + BASE64_MARKER.length), Base64.DEFAULT) }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
```

然后把 `QrLoginUi` 里的 `imageData: String` 换成 `imageBytes: ByteArray`，`AsyncImage(model = bytes)`。
Coil 对 `ByteArray` 有内建支持。

**How to apply**：把「网络取回的图片」交给图片库之前，先确认图片库认识这种载体。`AsyncImage` 的
静默失败会伪装成「布局问题」——我当时先去怀疑背景色、内边距、尺寸，绕了三圈才回到数据类型。

另一个副作用：`data class` 里放 `ByteArray` 时，自动生成的 `equals` 比的是引用。这里的语义恰好正确
（图片永远整体替换），但如果哪天需要按内容比较，必须手动覆写，否则每次 state copy 都被当成有变化。

顺带记一条测试环境的坑：`uiautomator dump` 会缓存上一次的层级，设备上该服务卡死之后会一直返回旧坐标。
现象是「截图显示的控件位置」和「dump 报的 bounds」不一致，此时所有基于 dump 的点击都会打空。
`adb kill-server && adb start-server` 能恢复；判断依据是两者必须一致。

# [ERR-20261020-003] `.sp` 在 Canvas 里会再乘一次 density，布局像素与绘制像素必须换算一次

**错误现象**：fume 模式（文章排版 + 运镜）在模拟器上首次运行时，每个字块的字号大约是布局预期的三倍，
块与块严重重叠，整页糊成一团。

**根因**：布局规划器用 Canvas 的真实像素（1080×2400）计算字号，得到 `block.fontPx ≈ 28`，含义是
「28 个物理像素」。绘制时我写成：

```kotlin
fontSize = block.fontPx.sp     // 28.sp
```

`Float.sp` 扩展会把数值乘以 `density * fontScale`。模拟器 density 约 2.75，于是 28.sp 实际渲染成
约 77px —— 正好是布局宽度的 2.75 倍，所以块重叠。

`TextUnit` 是逻辑单位，而布局用的是物理像素。两者之间**必须显式换算一次**：

```kotlin
val density = LocalDensity.current
fun pxToTextUnit(px: Float): TextUnit = (px / (density.density * density.fontScale)).sp
```

**修复方式**：在 Composable 顶部算出 `pxToTextUnit`，同时用于测量与绘制，并通过参数传进 `DrawScope`
扩展函数（DrawScope 不是 Composable，读不到 LocalDensity）。

**How to apply**：只要布局逻辑（纯函数、可单测）和渲染（Compose Canvas）共用一套尺寸，就必须先确定
**唯一的坐标单位**，并只在边界处换算一次。这里的单位选择是「物理像素」，因为 Canvas 的 `size` 是像素，
而 `.sp`/`.dp` 是逻辑单位。

同类错误的症状很好认：**渲染结果与布局结果差一个 density 倍数**。看到「文字比预期大三倍」「间距全部
偏移」时先怀疑单位，而不是去调布局参数——我在真正定位前先怀疑了纸面宽度、相机缩放、块宽适配，都是白跑。

一个附带教训：`Layout` 里读到的 `size` 在 Canvas 的 draw 阶段是像素，但 `Modifier.fillMaxSize()` 的
父容器如果是 `dp` 约束，两者数值可能巧合地接近，从而掩盖问题。不要靠数值巧合，显式换算。

# [ERR-20261020-004] 在 Canvas 的 draw 里写 state 会触发「draw 中读 state」并可能自旋

**错误现象**：fume 首帧之后日志偶发 Compose 的 state-read-in-draw 警告；把 Canvas 尺寸写回 state 后，
布局在每次绘制时都被判为变化，`remember(article)` 反复失效，帧循环跟着重启。

**根因**：我需要在首次绘制前知道 Canvas 尺寸（布局要用它），但尺寸只有绘制时才有。写成：

```kotlin
Canvas(...) {
    canvasWidth = size.width      // 在 draw 作用域里写 SnapshotState
    ...
}
```

这是双向依赖：draw 写 state → state 变更导致重组 → 重新 draw。Compose 把它当作违规用法。

**修复方式**：改用 `Modifier.onSizeChanged { }` 捕获尺寸，draw 保持只读：

```kotlin
Canvas(modifier = Modifier.fillMaxSize().onSizeChanged { canvasWidth = it.width.toFloat() }) { ... }
```

**How to apply**：`Canvas` 的 draw lambda 只能读 state。任何「绘制时才知道、但布局需要」的量
（尺寸、布局方向、字体度量）都应该通过 `onSizeChanged` / `onGloballyPositioned` /
`SubcomposeLayout` 拿到，而不是在 draw 里回写。

顺带一个相关坑：`LaunchedEffect(positionSec)` 这种以高频播放位置为 key 的帧循环，每 0.25 秒就会
cancel 并重启一次，`lastFrameNanos` 随之重置，`dt` 永远是默认值。正确做法是 effect 只 key 在
article 上，播放位置用 `rememberUpdatedState` 读取。

# [ERR-20261020-005] 换行只发生在词之间时，单个超宽词会直接溢出画布

**错误现象**：Classic 模式播放 `长句测试.lrc` 时，第一句「这是一句相当长的歌词用来验证分栏效果」
横向超出屏幕，两侧各被裁掉一部分。短句正常。

**根因**：`layoutWords` 的贪心换行只在**两个词之间**考虑断行：

```kotlin
if (projected > maxWidth && currentRow.isNotEmpty()) { /* 换行 */ }
```

`currentRow.isEmpty()` 时无条件接受第一个词，哪怕它比整行还宽。中文歌词通常不会触发，因为
`buildTimedWords` 把 CJK 按单字拆开；但两种情况会中招：

1. 整行只有一个「词」——某些 LRC 的英文长句没有空格，或解析器把整行当一个 token；
2. 字号偏大时，即使是几个 CJK 字组成的词也可能超过可用宽度。

**修复方式**：先把换行逻辑抽成纯函数 `RowLayout.flow`（可单测），并在绘制前按最宽词计算一次缩放：

```kotlin
val widest = measured.maxOfOrNull { it.layout.size.width.toFloat() } ?: 0f
val fitScale = RowLayout.fitScale(widest, size.width)   // 已适配则返回 1
if (fitScale < 1f) { /* 用缩小后的字号重新测量 */ }
```

`fitScale` 有 0.55 的下限：再小的字号已经不可读，那时应该断词而不是继续缩，所以超宽情况仍然
允许溢出，但至少不会把整行压成一条细线。

**How to apply**：任何「按元素流式排布」的布局都要显式回答「单个元素比容器还宽时怎么办」。
常见答案有三种——缩字号、断元素内部、允许溢出——必须选一个并写下来。默认行为往往是第一种意义上
的「什么都不做」，而它的表现是内容被裁掉，看起来像布局 bug 而不是数据问题。

顺带一条测试策略：这类问题**在 Composable 里写就测不到**，因为断言需要真实的 `TextMeasurer`。
把它抽成 `RowLayout` 之后，15 个用例可以在纯 JVM 上覆盖所有边界（单个超宽、恰好相等、空输入、
gap 为 0、行宽与放置求和一致）。抽出来的成本不到十分钟，换来的是这个 bug 不可能再回来。

# [ERR-20261020-006] 全页模式下上一句的「下一句预览」会叠在渲染器上

**错误现象**：Cappella（聊天气泡）截图里，气泡流底部压着两行灰字——「第三句歌词」和
「Second line translation」。它们不属于聊天流，但出现在同一个位置。

**根因**：`FoliaScreen` 里有一块与渲染模式无关的「下一句预览 + 当前句翻译」：

```kotlin
visible = nextLine != null && state.lyricMode != LyricMode.STILL
```

这个条件只排除了 Still——因为 Still 自己画三行。但 Fume 和 Cappella 是**全页模式**，它们各自
已经有完整的画面（Fume 把整首歌词排版在纸面上，Cappella 把已唱的行排成聊天记录），预览对它们
不是补充而是**遮挡**。

**修复方式**：把排除条件改成「逐行模式的白名单」，而不是逐个排除全页模式：

```kotlin
visible = nextLine != null &&
    state.lyricMode != LyricMode.STILL &&
    state.lyricMode != LyricMode.FUME &&
    state.lyricMode != LyricMode.CAPPELLA
```

**How to apply**：给渲染模式加属性（例如 `rendersWholePage`），让共享的 chrome 用属性判断，
而不是在条件里枚举模式。每加一个新模式都要回头改共享逻辑，是这类 bug 的固定来源；用属性表达
「这个模式自己负责整个画面」之后，新模式的接入点就只有一处。

顺带一个同类问题：Cappella 的聊天流需要**底部对齐**（最新一条贴着播放栏），但歌词容器是垂直
居中的，且容器高度写死。聊天列表用固定高度会随消息数变化而上下跳。改成 `weight(1f, fill=false)`
让它在列布局里吃掉剩余空间后，底部锚点才稳定。

# [ERR-20261020-007] 同包内同名顶层函数会冲突，即使一个是 internal 一个是 private 也不行

**错误现象**：给 Cappella 写 Canvas 时新增了一个 `lerpColor` 颜色混合函数，编译报：

```
Overload resolution ambiguity between candidates
Conflicting overloads: ...
```

**根因**：`LyricCanvas.kt` 里已经有一个 `private fun lerpColor(from, to, fraction)`。
Kotlin 的可见性只影响**跨文件能否引用**，不影响**同包内声明冲突**——两个顶层函数签名相同就是
冲突，与可见性无关。

**修复方式**：重命名为 `mixChatColor`，并在注释里写明为什么不用通用的名字。

**How to apply**：在一个按功能分文件的包里，通用的小工具名（`lerpColor`、`clamp`、`splitGraphemes`）
很容易撞车。既然 `FumeMath` 已经把这类工具收在一起了，新代码应该优先复用 `FumeMath.clamp` 之类
的既有实现，而不是再写一份。真要新增，名字带上所属模块（`mixChatColor`）比让它看起来通用更安全。

# [ERR-20261021-001] 歌词区底部内边距是猜的，比真实播放栏矮两倍

**错误现象**：Cadenza 模式下"下一句"预览和译文压在曲目标题、歌手名上；Cappella 的最新气泡
压在播放按钮上。两个模式的表现不同，但同一时刻截图能看到它们都落在 `长句测试 / 测试` 这两行
文字上。

**根因**：歌词 `Column` 用 `.align(Alignment.Center)` 在整个屏幕高度上垂直居中，底部只留
`padding(bottom = 120.dp)`。实际播放栏（标题+歌手+播放行+图标行+搜索框+safe inset）在
1080×2400 的设备上约 380dp。这 260dp 的缺口全部由歌词区自己承担，于是任何靠近底部的
内容——预览、译文、底部锚定的聊天列表——都会落进播放栏。

这个值当初是照着"大概够"填的，而且当时只有一个模式（Classic 的单词居中）根本不会触到底部，
所以问题一直藏着，直到 Cadenza（4 行错落网格，很高）和 Cappella（底部锚定）先后接进来。

**修复方式**：不要猜播放栏高度，测它。

```kotlin
var transportHeightPx by remember { mutableIntStateOf(0) }
val transportHeight = with(LocalDensity.current) { transportHeightPx.toDp() }
// 歌词列
.padding(top = safeInsets.calculateTopPadding(), bottom = transportHeight)
// 播放栏列
.onSizeChanged { transportHeightPx = it.height }
```

首帧 `transportHeightPx` 为 0，歌词区会短暂占满整屏再收缩；因为同一帧内播放栏也完成测量，
实际不会看到闪烁。

**How to apply**：任何"底部留白"常量都是对另一个组件高度的赌注，而那个组件会因为加一行图标、
加一个搜索框、字体缩放而变高。只要布局是"内容在 X 之上、X 的高度可变"，就应该用
`onSizeChanged` 把 X 的实测高度喂回来，而不是写死一个数字。顺带一提，写死的高度在不同字体
缩放下必然错，而 Compose 的测量-布局本来就提供了正确的通道。

**同一批里还发现**：我一开始把 Cappella 的 `Modifier.height(420.dp)` 换成了
`Modifier.weight(1f, fill = false)`，理由是"聊天列表应该吃满剩余空间"。这是错的——`weight`
分配的是**歌词列内部**的剩余空间，而歌词列底部只有 120dp 内边距，所以"剩余空间"实际上伸到
了播放栏下面，气泡直接压在播放按钮上。底部锚定的正确做法是让容器本身不越界（见上面的实测
高度），而不是在容器内部要更多高度。

# [ERR-20261021-002] 连续弹簧值喂给布局，导致"当前行"永远不存在

**错误现象**：pendolo 在模拟器上齿轮转得很漂亮，但四行歌词全是同一亮度的灰色——没有焦点行、
没有加大字号、逐字扫光完全不跑。截图看起来"能用"，只是不像卡拉 OK。

**根因**：`PendoloWheelGeometry.layout` 只有在 `targetLineIndex` 是**整数**时才把某一行标记为
`isActive`：

```kotlin
val isWholeTarget = targetLineIndex == floor(targetLineIndex)
val activeIndex = if (isWholeTarget && inRange) targetLineIndex.toInt() else -1
```

这个设计本身是对的——桌面版用 `lastValid + 0.5` 表示"正在间奏，没有当前行"。但我把逐帧弹簧
的连续值 `escapementValue` 直接传了进去，它几乎从不恰好等于整数，于是每一帧都是
`activeIndex = -1`，焦点行从头到尾没出现过。

**修复方式**：把两件事分开——**布局用离散锚点，旋转用弹簧偏移**：

```kotlin
// 布局：整数/半整数的锚点
lineItems = PendoloWheelGeometry.layout(targetLineIndex = target, ...)
// 旋转：弹簧当前位置与目标之差
wheelRotationDeg = -(escapementValue - targetFloat) * angleStepRad * (180f / PI.toFloat())
```

这正是桌面版的结构：整数 `targetLineIndex` 进布局，`useTransform(tickSpring, ...)` 只作用在容器
的 `rotate` 上。我一开始把"动画中的索引"当成了一个量，实际上它是两个量。

**How to apply**：凡是"有一个离散判定 + 一个连续过渡"的功能（当前行/当前页/当前选中项），
先把它们当两个独立的量写出来，再看是否需要合并。合并成"一个小数"会让离散判定静默失效，
而失效的表现往往只是"不好看"，不是报错。

# [ERR-20261021-003] 嵌套旋转被压成一次相加，歌词冻结在原地

**错误现象**：pendolo 的歌词斜得很厉害，而且轮盘转动时文字纹丝不动地停在原处。

**根因**：桌面版是三层层嵌套的变换：

```
<div rotate={wheelRotationDeg} transformOrigin={center}>      // 整个轮轨绕轮心
  <div rotate={textRotationCorrectionDeg} transformOrigin={left center}>  // = -0.65 × wheel
    <div rotate={angleDeg * 0.35}>
```

合成后：字形朝向 = `0.35 × (wheel + angle)`，而**锚点位置随 wheel 一起绕轮心移动**。
我写成了 `angleDeg + wheel + correction + angleDeg*0.35` 一次相加，既没让位置跟着转，
又让倾角多算了整整一个 `angleDeg`。

**修复方式**：显式算锚点位置，再只施加合成后的那一层旋转：

```kotlin
val anchor = rotateAbout(item.x, item.y, centerX, centerY, wheelRotationDeg)
val totalRotation = 0.35f * (wheelRotationDeg + item.angleDeg)
rotate(degrees = totalRotation, pivot = anchor) { ... }
```

`rotateAbout` 是显式算的，因为 Compose 的 `rotate` 只变换绘制作用域，锚点本身不会动。

**How to apply**：把嵌套变换"化简成一次"之前，先确认两件事——**位置**是否也参与变换、
各层的**旋转中心**是否相同。CSS 里嵌套 `rotate` 自带 `transformOrigin` 语义，搬到 Canvas
时必须自己分别实现，否则漏掉的通常是位置那一半。

# [ERR-20261021-004] 暂停后测"是否静止"，采到了点击引发的重组

**错误现象**：想验证"暂停时齿轮应该停"，连续两次测得暂停态的像素差异（5310）反而比播放态
（752）大 7 倍，看起来像是暂停后动得更厉害。

**根因**：点击播放/暂停按钮会让播放栏状态变化，触发整屏重组。我点完立刻连拍两帧，
正好夹在这次重组中间，测到的差异是**布局重排**（曲目标题、图标、进度文字都在移位），
不是动画。真正的动画差异只有几百个采样点。

**修复方式**：点完之后等界面稳定（>2s）再采样：

```bash
adb shell input tap 137 1820; sleep 3
adb screencap > a.png; sleep 1.5; adb screencap > b.png
# settled paused diff: 0
```

**How to apply**：用帧差判断"有没有动画"时，任何会改变 UI 状态的操作之后都要先等稳定期。
否则测到的是重排噪声，而且这类噪声**比动画本身大一个数量级**，会给出完全相反的结论。
更稳的做法是先把差异按行聚合看一眼分布——集中在一两条横带上是重排，散布在整个区域才是动画。
