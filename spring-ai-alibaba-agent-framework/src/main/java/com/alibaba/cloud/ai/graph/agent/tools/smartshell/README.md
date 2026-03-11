# SmartShellTool - 智能 Shell 工具

一个具备**自主思考能力**的 Shell 工具，能够自动检测环境、安装依赖、切换执行后端（本地/SSH/Docker）。

## 🚀 新增功能：EnhancedSmartShellTool

增强版 SmartShellTool 支持：
- **多执行后端** - 本地 Shell、SSH 远程、Docker 容器
- **自动安装缺失工具** - Python、Node.js、Git、Docker、kubectl 等
- **URI 格式执行** - `ssh://user@host/command`
- **Skill 友好** - 专为 Agent Skill 设计

---

## 功能特性

### 1. 自动 Shell 环境检测

自动检测系统上可用的 Shell 环境（按优先级排序）：

**Windows:**
- PowerShell (pwsh/powershell) - 优先
- WSL (Windows Subsystem for Linux)
- Git Bash
- cmd.exe - 最后备选

**macOS:**
- zsh - 优先 (Catalina+ 默认)
- bash
- sh

**Linux:**
- bash - 优先
- zsh
- sh

### 2. 错误自动分析和修复

自动识别以下错误类型并提供修复建议：

| 错误类型 | 自动检测 | 修复建议 |
|---------|---------|---------|
| 命令不存在 | ✅ | 提供安装命令 |
| Python 模块缺失 | ✅ | pip install 建议 |
| npm 包缺失 | ✅ | npm install 建议 |
| 权限不足 | ✅ | sudo 建议 |
| 网络错误 | ✅ | 检查连接建议 |
| 编译错误 | ✅ | 检查依赖建议 |

### 3. 自动修复模式

启用 `autoFix` 后，工具会自动：
1. 检测到缺失的依赖
2. 尝试自动安装
3. 重试原始命令

### 4. 备选 Shell 切换

当命令在当前 Shell 失败时，自动尝试：
- 替代命令（如 `python3` 代替 `python`）
- 备选 Shell（如 WSL 代替 PowerShell）

### 5. SSH 远程执行 (新增 🆕)

通过 WSL + sshpass 实现 SSH 密码认证：

```java
// 方法 1: 使用 SSH 工具
smartShell.executeSsh("10.1.120.166", 22, "root", "password", "uname -a", context);

// 方法 2: 使用 URI 格式
smartShell.execute("ssh://root@10.1.120.166/uname -a", null, true, context);
```

### 6. 自动安装工具 (新增 🆕)

```java
// 确保 Python 可用（不存在则自动安装）
smartShell.ensure("python", true, context);

// 安装 Docker
smartShell.install("docker", context);

// 执行命令（自动安装缺失的依赖）
smartShell.execute("python script.py", null, true, context);
```

**支持自动安装的工具：**
- Python、pip、Node.js、npm
- Git、Java、Maven、Gradle
- Docker、kubectl
- sshpass、curl、wget
- make、gcc、build-essential

## 使用示例

### 基本使用

```java
// 创建工具
SmartShellTool shellTool = SmartShellTool.builder("/workspace")
    .withAutoFix(true)
    .withTryAlternativeShells(true)
    .withVerboseErrors(true)
    .build();

// 在 Agent 中使用
ReactAgent agent = ReactAgent.builder()
    .model(chatModel)
    .tools(ToolCallbacks.from(shellTool))
    .build();

// Agent 会自动调用 shell
AssistantMessage response = agent.call("帮我列出当前目录的文件");
```

### 使用 Hook（推荐）

```java
SmartShellToolAgentHook hook = SmartShellToolAgentHook.builder()
    .workspaceRoot(System.getProperty("user.dir"))
    .autoFixEnabled(true)           // 启用自动修复
    .tryAlternativeShells(true)     // 启用备选 Shell
    .verboseErrors(true)            // 详细错误信息
    .commandTimeout(120000)         // 命令超时 2 分钟
    .build();

ReactAgent agent = ReactAgent.builder()
    .model(chatModel)
    .hooks(List.of(hook))
    .build();
```

### 检测 Shell 环境

```java
SmartShellTool tool = SmartShellTool.builder("/workspace").build();

// 检测可用 Shell
ShellEnvironmentList shells = tool.detectShellEnvironments();
shells.getShells().forEach(s -> {
    System.out.println("Shell: " + s.getType());
    System.out.println("Path: " + s.getPath());
    System.out.println("Priority: " + s.getPriority());
});
```

### 检查命令是否可用

```java
// 检查命令是否存在
CommandAvailableResult result = tool.checkCommandAvailable("python", toolContext);
if (!result.isAvailable()) {
    System.out.println(result.getMessage());  // 输出安装建议
}
```

## 与原始 ShellTool2 对比

| 功能 | ShellTool2 | SmartShellTool |
|-----|------------|----------------|
| 基本命令执行 | ✅ | ✅ |
| 会话持久化 | ✅ | ✅ |
| 自动 Shell 检测 | ❌ | ✅ |
| 错误分析 | ❌ | ✅ |
| 自动修复 | ❌ | ✅ |
| 备选 Shell | ❌ | ✅ |
| 安装建议 | ❌ | ✅ |
| WSL 支持 | ❌ | ✅ |

## 配置选项

### SmartShellTool 配置

```java
SmartShellTool.builder("/workspace")
    .withAutoFix(true)                    // 启用自动修复
    .withTryAlternativeShells(true)       // 启用备选 Shell
    .withVerboseErrors(true)              // 详细错误输出
    .withCommandTimeout(60000)            // 命令超时（毫秒）
    .withMaxOutputLines(1000)             // 最大输出行数
    .withMaxOutputBytes(1024 * 1024)      // 最大输出字节数
    .withEnvironment(Map.of("KEY", "value"))  // 环境变量
    .build();
```

### 错误恢复策略

工具会自动分析错误并提供修复建议：

```java
// 示例：Python 命令不存在
String output = "'python' is not recognized...";
ErrorAnalysis analysis = strategy.analyze("python script.py", output, 1);

// 结果：
// type: COMMAND_NOT_FOUND
// missingCommand: python
// suggestedFix: "Python not found. Install options:\n" +
//              "1. winget install Python.Python.3.11\n" +
//              "2. Download from https://www.python.org/downloads/"
```

## 架构图

```
┌─────────────────┐
│  SmartShellTool │
└────────┬────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
┌─────────┐ ┌─────────────────────┐
│ Session │ │   ErrorRecovery     │
│ Manager │ │      Strategy       │
└────┬────┘ └─────────────────────┘
     │              │
     │    ┌─────────┴─────────┐
     │    │                   │
     ▼    ▼                   ▼
┌─────────────────┐  ┌─────────────────┐
│  ShellEnvironment │  │  ErrorAnalysis  │
│    Detector      │  │   Suggestion    │
└─────────────────┘  └─────────────────┘
```

## 测试

运行测试：

```bash
./mvnw test -pl :spring-ai-alibaba-agent-framework \
  -Dtest=SmartShellToolTest
```

## 注意事项

1. **Windows 上建议使用 PowerShell**，功能最完善
2. **WSL 需要单独安装**，但提供最完整的 Linux 兼容性
3. **自动修复会执行安装命令**，请确保有网络连接和适当权限
4. **生产环境建议关闭 autoFix**，改为手动确认修复建议
