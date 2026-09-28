# Speak It Todo (说一嘴)

一款 AI 驱动的智能待办应用，支持手机和手表端，用 AI 帮你从文字或图片中提取待办事项。

## ✨ 功能特性

- **AI 智能提取**：从文字或图片中自动识别并提取待办事项
- **多模型支持**：支持 DeepSeek、豆包视觉版、通义 Qwen-VL 等多种 AI 模型
- **智能路由**：文本场景和图片场景自动选择最合适的模型
- **手机 + 手表**：支持 Android 手机和 Wear OS 手表
- **BLE 同步**：手表和手机之间通过蓝牙低功耗同步数据
- **Web/PWA 支持**：提供网页版，可安装为 PWA 应用
- **离线使用**：支持离线缓存，网络不佳时也能查看待办
- **暗色主题**：支持系统级暗色主题

## 🏗️ 项目结构

```
saym/
├── shared/          # 共享模块（AI 提炼、数据模型、加密工具）
├── backend/         # 后端服务（Ktor）
├── phone-app/       # Android 手机应用
├── watch-app/       # Wear OS 手表应用
└── settings.gradle.kts
```

## 🚀 快速开始

### 前置条件

- JDK 17+
- Android Studio (用于手机和手表应用)
- Gradle 8.x

### 构建项目

```bash
# 使用阿里云镜像（可选，国内加速）
export SAYM_USE_MIRROR=1

# 构建整个项目
cd saym
./gradlew build
```

### 运行后端

```bash
cd saym
./gradlew :backend:run
```

后端默认监听 `8080` 端口。

### 运行手机应用

使用 Android Studio 打开 `saym/phone-app` 目录，连接设备或模拟器后运行。

### 运行手表应用

使用 Android Studio 打开 `saym/watch-app` 目录，连接 Wear OS 设备后运行。

## ⚙️ 配置

### AI 模型配置

在手机应用的「设置 → 配置中心 → AI 模型」中配置：

| 模型 | 用途 | 说明 |
|------|------|------|
| DeepSeek | 文本提取 | 默认文本模型 |
| 豆包视觉版 | 图片提取 | 支持图片识别 |
| 通义 Qwen-VL | 图片提取 | 备选视觉模型 |

### 环境变量

| 变量 | 说明 |
|------|------|
| `SAYM_USE_MIRROR=1` | 使用阿里云 Maven 镜像 |

## 📱 功能说明

### 输入方式
- **文字输入**：直接输入待办内容
- **图片输入**：拍照或选择图片，AI 自动识别
- **语音输入**：支持语音转文字（浏览器环境）

### AI 提炼
- 自动提取任务标题、截止日期、学科分类
- 支持识别相对时间（"明天"、"下周五"等）
- 低置信度结果标记"请确认"

### 待办管理
- 四种视图：今日、本周、逾期、全部
- 支持批量完成
- 完成率看板

## 🔧 技术栈

- **语言**：Kotlin
- **手机端**：Android (Room, BLE)
- **手表端**：Wear OS
- **后端**：Ktor, PostgreSQL
- **AI**：OpenAI 兼容 API (DeepSeek, 豆包, 通义)
- **构建**：Gradle (Kotlin DSL)

## 📄 License

MIT License
