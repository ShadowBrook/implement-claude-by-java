# Claude Agent Loop - Java Implementation

Java 版本的 Claude Agent Loop 实现
## 功能说明

这个实现复刻了[Learn Claude Code](https://learn.shareai.run/) agent loop 核心功能：

1. **核心循环模式**：持续调用 LLM 直到模型停止使用工具
2. **Bash 工具**：支持执行 shell 命令，包含安全检测
3. **REPL 交互**：命令行交互模式，支持多轮对话

## 构建和运行

### 前置要求

- Java 17+
- Maven 3.6+
- Anthropic API Key

### 配置

```bash
# 复制环境配置示例
cp .env.example .env

# 编辑 .env 文件，填入你的 API Key
```

### 编译

```bash
mvn clean compile
```

### 运行

```bash
# 使用 exec-maven-plugin 运行
mvn exec:java

# 或者直接指定参数
mvn exec:java -Dexec.args="--api-key your_key"
```

## 架构说明

```
┌──────────┐      ┌───────┐      ┌─────────┐
│   User   | ---> |  LLM  | ---> |  Tool   |
|  prompt  |      |       |      | execute |
└──────────┘      └───┬───┘      └────┬────┘
                      ^               |
                      |  tool_result  |
                      └───────────────┘
                      (循环继续)
```

## 项目结构

```
implement-claude-by-java/
├── pom.xml                          # Maven 配置
├── src/main/java/com/example/
│   ├── AgentLoopApp.java            # 主程序和 CLI
│   └── BashTools.java               # Bash 工具实现
├── .env.example                     # 环境变量示例
└── .gitignore
```

## 核心代码

Agent Loop 模式：

```java
private void agentLoop() {
    while (true) {
        // 调用 LLM
        ChatResponse response = chatModel.chat(request);

        // 如果没有工具调用，结束
        if (no tool use) return;

        // 执行工具并收集结果
        execute tools and collect results

        // 将结果追加到消息历史
        append results to history
    }
}
```
