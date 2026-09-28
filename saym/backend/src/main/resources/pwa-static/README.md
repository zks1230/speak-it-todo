# 说一嘴 · Web/PWA 前端（静态资源）

本目录是后端 `StaticHandler` 直接托管的 Web/PWA 前端（决策 **D10**，信息架构见需求文档 **§5.2**）。
纯静态、零构建、零外部依赖（无 CDN / 无外部字体 / 无图片外链），内网与离线均可使用。

---

## 1. 怎么访问

后端启动后（默认 `8080`），用浏览器打开：

```
http://<后端所在主机>:8080/
```

`StaticHandler` 的查找顺序（`Main.kt`，注意这两个都是**相对进程工作目录的目录路径**，不是 jar 内 classpath 资源）：

```kotlin
val staticDir = File("pwa-static").takeIf { it.exists() }
    ?: File("src/main/resources/pwa-static").takeIf { it.exists() }
```

1. `<工作目录>/pwa-static/`（优先级高，适合临时覆盖）
2. `<工作目录>/src/main/resources/pwa-static/`，即本目录（当且仅当工作目录就是 `backend/`）

具体文件找不到时会回落到 `index.html`（单页应用），但真实存在的文件会优先命中。

**因此，能正常看到页面的典型场景**：

| 启动方式 | 工作目录 | 是否命中本目录 |
| --- | --- | --- |
| Gradle `:backend:run`（`gradle run`） | `saym/backend/` | ✅ 命中第 2 条 |
| `backend/build/install/backend/bin/backend`（在 `backend/` 下启动） | `saym/backend/` | ✅ 命中第 2 条 |
| `java -jar backend.jar`（在别的目录启动，如 `saym/`） | `saym/` | ❌ 两条都不存在 |

> ⚠️ **部署提醒**：`Main.kt` 目前**不会**从 jar 的 classpath 读取资源，所以仅仅把静态文件放进 `src/main/resources/` 并**不能**让「在任意目录 `java -jar`」直接显示出完整前端（会显示后端内置的提示页）。
> 若要让发布包自带前端，二选一：
> 1. 把本目录整体拷到运行目录下并命名为 `pwa-static/`（推荐，零代码改动）；
> 2. 或让后端从 classpath 读（需改 `Main.kt`/`StaticHandler`，例如用 `javaClass.getResourceAsStream("/pwa-static/index.html")` 或启动时把资源解包到临时目录）—— 这属于后端改动，不在本次前端交付范围内。

## 2. 怎么安装为 PWA

**Android Chrome / Edge**：打开页面 → 顶栏「安装到桌面」按钮（或浏览器菜单 →「安装应用 / 添加到主屏幕」）。

**iOS Safari**：打开页面 → 分享 → 「添加到主屏幕」。
（iOS 不支持 `beforeinstallprompt`，所以页面上的安装按钮会自动隐藏，属正常现象。）

**桌面 Chrome / Edge**：地址栏右侧的安装图标，或顶栏「安装到桌面」按钮。

安装后 `display: standalone`，离线也能打开界面（见第 4 节 Service Worker 策略）。

## 3. 文件用途

| 文件 | 用途 |
| --- | --- |
| `index.html` | 单页应用骨架：顶栏、输入区（F1.2/F1.3/F1.1）、提炼预览（F2.5/F2.6）、待办列表（F3.1）、看板（F3.8）、设置（F4.1/F6 只读）、底部导航、编辑弹窗（F3.3）、提示条 |
| `styles.css` | 全部样式：移动端优先，窄屏单列 / 宽屏（≥900px）两列；含 `prefers-color-scheme` 暗色主题；只用系统字体栈 |
| `app.js` | 应用逻辑：API 封装（统一 `X-Device-Id`）、图片 canvas 压缩、AI 提炼与草稿入库、待办增删改查、批量完成、看板、输入暂存重提、设备绑定、Service Worker 注册与安装提示、错误重试 |
| `manifest.webmanifest` | PWA 清单：`name` / `short_name` / `start_url` / `display=standalone` / `theme_color` / `background_color` / `icons`（192 + 512，含 maskable） |
| `sw.js` | Service Worker：预缓存 app shell 实现离线打开；`/api/` 一律走网络不缓存；导航请求网络优先、失败回落 `index.html` |
| `icon-192.png` | 192×192 PNG 图标（圆角 + 蓝紫渐变 + 白色对勾），用于 `apple-touch-icon`、favicon、PWA 普通图标 |
| `icon-512.png` | 512×512 PNG 图标（满幅方形，内容在 maskable 安全区内），用于 PWA 图标 / 启动画面 |
| `README.md` | 本文件 |

图标说明：本目录的 PNG 是**真实合法的 PNG**（8bit RGBA，IHDR/IDAT/IEND 三段 CRC 均校验通过），由 Node 的 `zlib` 手写 PNG 字节流生成，非占位假文件、非 base64 空图。图形是几何对勾，不含文字（生成环境无字体渲染）。

## 4. 功能与需求编号对照

| 需求 | 实现位置 |
| --- | --- |
| F1.2 文字输入 | `index.html` 的 `#textInput` + `app.js` `runExtract()`（`POST /api/extract`，`{"type":"TEXT","text":"..."}`） |
| F1.3 图片输入 | 拖拽 / 多选 / 拍照（`capture="environment"`）；`compressImage()` 用 canvas 压到最长边 1280px、JPEG 0.8，转 base64（去掉 `data:` 前缀）后 `{"type":"IMAGE","imageBase64":"...","mimeType":"image/jpeg"}`；多张图逐张调用后合并草稿 |
| F1.1 语音输入 | `setupSpeech()`：`window.SpeechRecognition \|\| window.webkitSpeechRecognition` 存在才显示麦克风按钮，结果填进文本框；不存在则隐藏（D6） |
| F1.4 输入暂存 | 「输入历史」区 `GET /api/inputs`；未处理的文字输入可「重提」（带 `inputId` + `saveInput:false` 重跑提炼，避免重复落暂存）；已处理的显示产出条数；图片因不入库显示「图片不支持重提」 |
| F2.5 预览可改 | 草稿卡片可改标题 / 截止日期 / 学科 / 备注 / 关键任务，单条「入库」或「全部入库」（`POST /api/todos`） |
| F2.6 模糊度标注 | `confidence < 0.7` 或 `needsConfirm === true` → 卡片高亮并显示「⚠ 请确认（置信度 xx%）」 |
| F3.1 列表视图 | 四个 Tab：今日 `/api/todos/today?scope=all`、本周 `/api/todos/week?scope=all`、逾期 `/api/todos/overdue?scope=all`、全部 `/api/todos?scope=all` |
| F3.2 完成标记 | 单条勾选 `PUT /api/todos/{id}` `{"isCompleted":true}`；「多选」模式下勾选多条 →「批量完成」`POST /api/todos/batch` `{"ids":[...],"isCompleted":true}` |
| F3.3 编辑 | 编辑弹窗 `PUT /api/todos/{id}`，可改 `title/deadline/subject/notes/isKeyTask/remindAt/remindOffsetMinutes`；清空截止日期发 `{"clearDeadline":true}`，清空定点提醒发 `{"clearRemindAt":true}`；删除 `DELETE /api/todos/{id}` |
| F3.8 完成率看板 | `GET /api/stats/completion?scope=all` → 本周完成 / 本周逾期 / 完成率进度条 / 连续完成天数 / 待办总数（后端 `completionRate` 是 0~1 小数，前端按百分比展示，兼容 >1 的百分数写法） |
| F4.1 设备绑定 | 首次进入生成 `web-<随机串>` 存 `localStorage`，有值即 `POST /api/devices` `{"deviceId":..,"name":"Web 浏览器","kind":"web"}`；所有请求带 `X-Device-Id`；设置区可改设备 ID 与后端地址 |
| F4.3 增量同步 | 设置 →「立即同步」`POST /api/sync`（`sinceUpdatedAt:null` 全量，`limit:500`），并写入上次同步时间戳 |
| F4.4 离线缓存 | 待办列表/看板结果缓存到 `localStorage`，网络失败时展示缓存并明确提示（附「重试」） |
| F5.1 / F5.5 提醒规则 | 设置面板只读展示 `GET /api/config` 的 `dailyPushHour/dailyPushMinute/dailyPushEnabled`、`keyTaskRemindEnabled`、`defaultRemindOffsetMinutes` |
| F6.2 / F6.3 配置中心 | 只读展示各模型是否已配置（后端只回 `"***"` 或 `""`，前端仅显示「已配置 / 未配置」，**绝不显示明文**）与分流规则；**Web 端不做配置写入**，写入留在手机端主密码门禁后（F6.1 / D13） |

### localStorage 键

| 键 | 含义 |
| --- | --- |
| `saym.deviceId` | F4.1 设备 ID |
| `saym.baseUrl` | 后端地址（空串 = 同源） |
| `saym.lastSyncAt` | F4.3 上次同步时间戳 |
| `saym.boundDevice` | 已成功绑定过的设备 ID（避免每次刷新都重复绑定） |
| `saym.cache.todos` / `saym.cache.stats` | F4.4 离线兜底缓存 |

### 实现细节提示（踩坑记录）

- `/api/extract` 的 `drafts` 在 JSON 里可能是「字符串包着数组」，`normalizeDrafts()` 做了 `typeof === 'string'` 的二次 `JSON.parse` 兜底。
- 后端 `today / week / overdue` 三个分组是**按日期字符串比较**的，所以前端入库时把 `deadline` 统一归一化成 `yyyy-MM-dd`，避免 AI 返回完整 ISO 时间导致条目只出现在「全部」里。
- 所有插入 DOM 的 AI / 用户内容都走 `textContent`（`app.js` 的 `h()` 构造器），不做 `innerHTML` 字符串拼接，规避 XSS。
- 修改任何静态文件后，请把 `sw.js` 里的 `CACHE = 'saym-pwa-v1'` 版本号加一，否则浏览器会继续使用旧缓存（`index.html` 与 `sw.js` 本身后端已设置 `Cache-Control: no-cache`）。
- `/api/` 请求在 Service Worker 中**直接放行给网络**（不 `respondWith`），确保待办数据永远实时且不落缓存。

## 5. 交付自检记录

| 检查项 | 结果 |
| --- | --- |
| `node --check app.js` / `sw.js` | 通过（无语法错误） |
| `manifest.webmanifest` JSON 解析 | 通过（字段齐全，图标路径均存在） |
| `index.html` 标签配平 / `styles.css` 花括号与圆括号配平 | 通过 |
| `app.js` 中 `$('id')` 引用 vs `index.html` 实际 id | 65 个引用全部命中，无缺失 |
| `index.html` 本地资源引用（manifest / icon / css / js） | 全部存在 |
| `sw.js` 预缓存清单 vs 目录实际文件 | 7 项全部存在（`'./'` 由后端回落 index.html） |
| `manifest` icons 引用的文件 | 存在 |
| 外部网络资源（CDN / 外链字体 / 外链图片） | 无（仅有一处输入框 placeholder 示例地址 `http://192.168.1.5:8080`，非资源引用） |
| `innerHTML` / `outerHTML` / `document.write` | 0 次（仅注释中出现 `innerHTML` 字样） |
| `icon-192.png` / `icon-512.png` | 合法 PNG：签名 `89504E470D0A1A0A`、IHDR/IDAT/IEND 三段 CRC32 全部校验通过、尺寸分别为 192×192 与 512×512（colorType=6 RGBA） |
| 契约逻辑单测（VM 中加载 `app.js` 真实函数） | 27 项断言全部通过：drafts 字符串/数组双兼容、deadline 归一化为 `yyyy-MM-dd`、F2.6 阈值判定、今天/明天/逾期文案、`datetime-local` ↔ ISO 往返 |

**未能验证的部分**：本机没有 JDK，也没有浏览器自动化环境（无 jsdom/playwright，且离线），因此**没有**做「启动后端 → 浏览器真实点击」的端到端联调。
上述验证是语法级 + 结构级 + 真实函数级（在 Node VM 里跑 `app.js` 的内部函数）。首次接入真实后端时请重点确认：

1. `/api/extract` 返回的 `drafts` 实际形态（代码已做字符串/数组双兼容）；
2. 图片 `imageBase64` 体积是否被后端接受（前端已压到最长边 1280px / JPEG 0.8）；
3. 宽屏两列布局与 iOS Safari 的「添加到主屏幕」路径。
