# Claude Agent Loop - Java Implementation

使用 Java 原生 HTTP 客户端实现的 Claude Agent Loop，复刻 [Learn Claude Code](https://learn.shareai.run/) 的核心功能。

## 示例模块

| 模块 | 说明 | 运行命令 |
|------|------|----------|
| **S01_agent_loop** | 基础 Agent 循环，持续调用 LLM 直到模型停止 | `mvn exec:java -Dexec.mainClass=com.example.S01_agent_loop` |
| **S02_tool_use** | 添加 Bash/Read/Write/Edit 工具支持 | `mvn exec:java -Dexec.mainClass=com.example.S02_tool_use` |
| **S03_todo_write** | Todo 工具 + 进度追踪 + 超时提醒机制 | `mvn exec:java -Dexec.mainClass=com.example.S03_todo_write` |
| **S04_subagent** | 子代理上下文隔离，任务委派 | `mvn exec:java -Dexec.mainClass=com.example.S04_subagent` |

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

## 工具列表

| 工具 | 描述 | 所属模块 |
|------|------|----------|
| `bash` | 执行 shell 命令 | S01+ |
| `read_file` | 读取文件内容 | S02+ |
| `write_file` | 写入文件 | S02+ |
| `edit_file` | 编辑文件 (替换文本) | S02+ |
| `todo` | 更新任务列表 | S03+ |
| `task` | 委派给子代理 | S04 |

## 安全特性

- **路径安全检查**: 所有文件操作限制在项目工作目录内
- **危险命令过滤**: 阻止 `rm -rf /`, `sudo`, `shutdown` 等命令
- **超时保护**: Bash 命令 120 秒超时
- **迭代限制**: 子代理最多 30 次迭代

## 参考资料

- [Learn Claude Code](https://learn.shareai.run/) - 原始 Python 实现
- [Anthropic API Docs](https://docs.anthropic.com/claude/reference/messages_post)
