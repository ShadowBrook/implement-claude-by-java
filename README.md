# Claude Agent Loop - Java Implementation

使用 Java 原生 HTTP 客户端实现的 Claude Agent Loop，复刻 [Learn Claude Code](https://learn.shareai.run/) 的核心功能。

## 示例模块

| 模块 | 说明 | 运行命令 |
|------|------|----------|
| **S01_agent_loop** | 基础 Agent 循环，持续调用 LLM 直到模型停止 | `mvn exec:java -Dexec.mainClass=com.example.S01_agent_loop` |
| **S02_tool_use** | 添加 Bash/Read/Write/Edit 工具支持 | `mvn exec:java -Dexec.mainClass=com.example.S02_tool_use` |
| **S03_todo_write** | Todo 工具 + 进度追踪 + 超时提醒机制 | `mvn exec:java -Dexec.mainClass=com.example.S03_todo_write` |
| **S04_subagent** | 子代理上下文隔离，任务委派 | `mvn exec:java -Dexec.mainClass=com.example.S04_subagent` |
| **S05_skill_loading** | 技能加载，按需注入领域知识 | `mvn exec:java -Dexec.mainClass=com.example.S05_skill_loading` |
| **S06_context_compact** | 三层上下文压缩，支持无限会话 | `mvn exec:java -Dexec.mainClass=com.example.S06_context_compact` |
| **S07_permission_system** | 权限系统，三种模式 + 规则引擎 + 用户确认 | `mvn exec:java -Dexec.mainClass=com.example.S07_permission_system` |

## 架构演进

```
┌─────────────────────────────────────────────────────────────────────┐
│  S01: Agent Loop                                                    │
│  ┌──────────┐    ┌───────┐    ┌─────────┐                          │
│  │   User   │ -> │  LLM  │ -> │  Bash   │                          │
│  └──────────┘    └───┬───┘    └────┬────┘                          │
│                     ^             |                                 │
│                     └─────────────┘                                 │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  S02: Tool Use                                                      │
│  ┌──────────┐    ┌───────┐    ┌─────────────────────────────┐      │
│  │   User   │ -> │  LLM  │ -> │ Bash │ Read │ Write │ Edit  │      │
│  └──────────┘    └───┬───┘    └─────────────────────────────┘      │
│                     ^                                             │
│                     └─────────────────────────────────────────────┘
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  S03: Todo Write                                                    │
│  ┌──────────┐    ┌───────┐    ┌─────────────────────────────┐      │
│  │   User   │ -> │  LLM  │ -> │ Bash │ Read │ Write │ Todo  │      │
│  └──────────┘    └───┬───┘    └─────────────────────────────┘      │
│                     ^                                             │
│                     │  ┌─────────────────────────────────────────┐ │
│                     └──│ TodoManager: [ ] [>] [x]  +  nag reminder│ │
│                        └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  S04: Subagent                                                      │
│  ┌───────────────────┐              ┌───────────────────┐          │
│  │  Parent Agent     │              │   Subagent        │          │
│  │  messages=[...]   │   ┌────┐     │  messages=[]      │          │
│  │  + task tool      │──>|task│────>|  (fresh context)  │          │
│  │                   │   └────┘     │  + bash/read/     │          │
│  │                   │  <summary>   │  write/edit       │          │
│  │                   │              │                   │          │
│  └───────────────────┘              └───────────────────┘          │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  S05: Skill Loading - Two-layer Knowledge Injection                 │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  System Prompt (Layer 1)                                    │   │
│  │  Skills available:                                          │   │
│  │    - pdf: Process PDF files [file-processing]               │   │
│  │    - code-review: Review code for bugs [quality]            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────┐    ┌───────┐    ┌─────────────────────────────┐     │
│  │   User   │ -> │  LLM  │ -> │ load_skill("pdf")           │     │
│  └──────────┘    └───┬───┘    └─────────────────────────────┘     │
│                     ^                                             │
│                     │  ┌─────────────────────────────────────────┐ │
│                     └──│ <skill name="pdf">                      │ │
│                        │   Full PDF processing instructions...   │ │
│                        │   Step 1: Extract text from PDF         │ │
│                        │   Step 2: Process images...             │ │
│                        │   </skill>                              │ │
│                        └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  S06: Context Compact - Three-layer Compression Pipeline            │
│                                                                     │
│  Every turn:                                                        │
│  ┌──────────────────┐                                               │
│  │ Tool call result │                                               │
│  └────────┬─────────┘                                               │
│           ▼                                                         │
│  [Layer 1: micro_compact]  ← 静默执行，每轮运行                      │
│    替换超过最后 3 个的工具结果为 "[Previous: used {tool_name}]"        │
│           ▼                                                         │
│  [Check: tokens > 50000?]                                           │
│     │            │                                                  │
│     no           yes                                                │
│     │            ▼                                                  │
│     │      [Layer 2: auto_compact]                                  │
│     │        保存完整对话到 .transcripts/                            │
│     │        让 LLM 总结对话                                         │
│     │        用 [summary] 替换所有消息                               │
│     │            ▼                                                  │
│     │      [Layer 3: compact tool]                                  │
│     │        模型调用 compact → 立即总结                             │
│     └──────────── 继续                                              │
│                                                                     │
│  Key insight: "The agent can forget strategically and work forever" │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  S07: Permission System - Safety Pipeline                           │
│                                                                     │
│  Every tool call passes through a permission pipeline:              │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Permission Pipeline                                        │   │
│  │                                                             │   │
│  │  [Step 0: Bash Validator]  ← 检查危险模式 (sudo, rm -rf)    │   │
│  │         │                                                   │   │
│  │         v                                                   │   │
│  │  [Step 1: Deny Rules]  ← 阻止 rm -rf /, sudo * 等           │   │
│  │         │                                                   │   │
│  │         v                                                   │   │
│  │  [Step 2: Mode Check]                                       │   │
│  │         ├── plan 模式：拒绝所有写操作                        │   │
│  │         ├── auto 模式：自动允许只读操作                      │   │
│  │         └── default 模式：继续                               │   │
│  │         │                                                   │   │
│  │         v                                                   │   │
│  │  [Step 3: Allow Rules]  ← 匹配允许规则                      │   │
│  │         │                                                   │   │
│  │         v                                                   │   │
│  │  [Step 4: Ask User]  ← 无规则匹配时询问用户                 │   │
│  │                                                             │   │
│  │  用户响应：y/n/always                                       │   │
│  │    - always: 添加永久允许规则                                │   │
│  │    - Circuit breaker: 连续 3 次拒绝后建议切换 plan 模式         │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  Permission Modes:                                                  │
│  ┌──────────┬──────────────────────────────────────────────────┐   │
│  │ default  │ 所有工具调用都需要用户确认（除非匹配规则）         │   │
│  │ plan     │ 拒绝所有写操作，允许只读操作                       │   │
│  │ auto     │ 自动允许只读工具，写操作询问用户                   │   │
│  └──────────┴──────────────────────────────────────────────────┘   │
│                                                                     │
│  Commands:                                                          │
│    /mode <default|plan|auto>  - 切换权限模式                        │
│    /rules                     - 查看当前规则列表                    │
│                                                                     │
│  Key insight: "Safety is a pipeline, not a boolean"                 │
└─────────────────────────────────────────────────────────────────────┘

```

## 快速开始

### 前置要求

- Java 17+
- Maven 3.6+
- Anthropic API Key

### 配置

```bash
# 复制环境配置示例
cp .env.example .env

# 编辑 .env 文件
ANTHROPIC_AUTH_TOKEN=your_api_key_here
MODEL_ID=claude-sonnet-4-20250514
ANTHROPIC_BASE_URL=https://api.anthropic.com
```

### 编译

```bash
mvn clean compile
```

### 运行示例

```bash
# 运行 S01 - 基础 Agent Loop
mvn exec:java -Dexec.mainClass=com.example.S01_agent_loop

# 运行 S04 - 子代理
mvn exec:java -Dexec.mainClass=com.example.S04_subagent
```

## 项目结构

```
src/main/java/com/example/
├── S01_agent_loop.java      # 基础循环 + Bash 工具
├── S02_tool_use.java        # 多工具支持 (bash/read/write/edit)
├── S03_todo_write.java      # Todo 工具 + 进度追踪
├── S04_subagent.java        # 子代理上下文隔离
├── S05_skill_loading.java   # 技能加载，按需注入领域知识
├── S06_context_compact.java # 三层上下文压缩，支持无限会话
├── S07_permission_system.java  # 权限系统，三种模式 + 规则引擎 + 用户确认
└── model/
    ├── ApiRequest.java      # API 请求体
    ├── ApiResponse.java     # API 响应体
    ├── Message.java         # 消息结构
    ├── MessageContent.java  # 消息内容 (text/tool_use/tool_result)
    ├── Tool.java            # 工具定义
    ├── ToolInputSchema.java # 工具参数 Schema
    ├── JsonInput.java       # 工具输入参数
    ├── TodoItem.java        # Todo 项
    └── TodoInput.java       # Todo 输入
```

## 核心代码模式

### Agent Loop

```java
private void agentLoop(List<Message> messages) {
    while (true) {
        ApiResponse response = callAnthropic(messages);

        if (!"tool_use".equals(response.getStop_reason())) {
            return;  // 模型完成，退出循环
        }

        // 执行工具并收集结果
        List<MessageContent> results = executeTools(response.getContent());

        // 将工具结果追加到消息历史
        messages.add(Message.createUserMessage(results));
    }
}
```

### 上下文隔离 (S04)

```java
// 父代理
List<Message> parentMessages = ...;  // 完整对话历史

// 子代理 - Fresh Context
List<Message> subMessages = new ArrayList<>();
subMessages.add(createUserMessage(prompt));  // 仅任务描述

// 子代理执行工具，返回摘要
String summary = runSubAgent(prompt);

// 父代理上下文保持干净
```

### 技能加载 (S05)

```java
// SkillLoader 扫描 skills/<name>/SKILL.md 文件
private static final Path SKILLS_DIR = Paths.get(System.getProperty("user.home"), "skills");
private static final SkillLoader SKILL_LOADER = new SkillLoader(SKILLS_DIR);

// Layer 1: 系统 prompt 中包含技能描述
systemPrompt = "You are a coding agent...\n\n" +
        "Skills available:\n" +
        SKILL_LOADER.getDescriptions();

// Layer 2: 按需加载完整技能内容
handlers.put("load_skill", input -> SKILL_LOADER.getContent(input.getSkill_name()));

// 返回格式：<skill name="pdf">完整技能内容</skill>
```

### 上下文压缩 (S06)

```java
// Layer 1: micro_compact - 每轮静默执行
private void microCompact(List<Message> messages) {
    // 收集所有 tool_result，保留最近 3 个
    // 将旧的工具结果替换为 "[Previous: used {tool_name}]"
}

// Layer 2: auto_compact - token 超过阈值时触发
if (estimateTokens(messages) > TOKEN_THRESHOLD) {  // 50000 tokens
    messages = autoCompact(messages);
}

// Layer 3: compact tool - 模型主动调用
if ("compact".equals(toolName)) {
    manualCompact = true;
    messages = autoCompact(messages);
}

// 压缩流程
private List<Message> autoCompact(List<Message> messages) {
    // 1. 保存完整对话到 .transcripts/transcript_<timestamp>.jsonl
    Path transcriptPath = saveTranscript(messages);
    
    // 2. 请求 LLM 生成摘要
    String summary = summarizeConversation(conversationText);
    
    // 3. 用摘要替换所有消息
    return List.of(
        Message.createUserMessage("[Conversation compressed. Transcript: " + transcriptPath + "]\n\n" + summary),
        Message.createAssistantMessage("Understood. I have the context from the summary. Continuing.")
    );
}
```

#### Token 估算

```java
// ~4 chars per token
private int estimateTokens(List<Message> messages) {
    StringBuilder sb = new StringBuilder();
    for (Message msg : messages) {
        sb.append(msg.toString());
    }
    return sb.length() / 4;
}
```

#### 转录文件目录结构

```
.transcripts/
├── transcript_1712236800.jsonl
├── transcript_1712237000.jsonl
└── transcript_1712237200.jsonl
```

每行格式：`{"role": "user/assistant", "content": [...]}`

#### 技能文件格式 (skills/pdf/SKILL.md)

```markdown
---
name: pdf
description: Process PDF files
tags: file-processing
---

完整的技能内容/指示...
Step 1: Extract text from PDF
Step 2: Process images...
```

## 工具列表

| 工具 | 描述 | 所属模块 |
|------|------|----------|
| `bash` | 执行 shell 命令 | S01+ |
| `read_file` | 读取文件内容 | S02+ |
| `write_file` | 写入文件 | S02+ |
| `edit_file` | 编辑文件 (替换文本) | S02+ |
| `todo` | 更新任务列表 | S03+ |
| `task` | 委派给子代理 | S04 |
| `load_skill` | 加载技能知识 | S05 |
| `compact` | 手动触发上下文压缩 | S06 |

## 权限系统 (S07)

### 权限检查流程

```
1. Bash 安全验证 → 检测危险模式 (sudo, rm -rf, 管道符等)
2. 拒绝规则 → 匹配 deny 规则则直接拒绝
3. 模式检查 → 根据当前模式决定行为
4. 允许规则 → 匹配 allow 规则则直接允许
5. 询问用户 → 默认行为
```

### 权限规则格式

```java
new PermissionRule("bash", null, "rm -rf /", "deny")   // 拒绝 rm -rf /
new PermissionRule("read_file", "*", null, "allow")    // 允许读取任何文件
```

| 字段 | 说明 |
|------|------|
| tool | 工具名称，支持 `*` 通配符 |
| path | 文件路径 glob 匹配 |
| content | 内容匹配（用于 bash 命令） |
| behavior | `allow`, `deny`, `ask` |

### 运行示例

```bash
mvn exec:java -Dexec.mainClass=com.example.S07_permission_system

# Permission modes: default, plan, auto
# Mode (default): 
```

### 安全特性

- **Bash 验证器**: 检测 `sudo`, `rm -rf`, shell 元字符等危险模式
- **连续拒绝保护**: 连续 3 次拒绝后建议切换到 plan 模式
- **always 选项**: 用户可选择"always"添加永久允许规则

## 安全特性

- **路径安全检查**: 所有文件操作限制在项目工作目录内
- **危险命令过滤**: 阻止 `rm -rf /`, `sudo`, `shutdown` 等命令
- **超时保护**: Bash 命令 120 秒超时
- **迭代限制**: 子代理最多 30 次迭代

## 参考资料

- [Learn Claude Code](https://learn.shareai.run/) - 原始 Python 实现
- [Anthropic API Docs](https://docs.anthropic.com/claude/reference/messages_post)
