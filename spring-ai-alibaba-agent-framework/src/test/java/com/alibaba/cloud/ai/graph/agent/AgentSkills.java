package com.alibaba.cloud.ai.graph.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.extension.tools.filesystem.FileSystemTools;
import com.alibaba.cloud.ai.graph.agent.hook.shelltool.ShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.smartshell.SmartShellToolAgentHook;
import com.alibaba.cloud.ai.graph.agent.tools.ShellTool2;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;


public class AgentSkills {

    private static final int MAX_TOOL_RESPONSE_LENGTH = 200;
    private static final int MAX_LINE_WIDTH = 100;
    // 辅助方法：截断过长的文本
    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    // 辅助方法：格式化长文本，自动换行
    private static String formatMultiline(String text, int maxLineWidth) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + maxLineWidth, text.length());
            sb.append(text, pos, end);
            if (end < text.length()) {
                sb.append("\n  ");
            }
            pos = end;
        }
        return sb.toString();
    }

    public static void main(String[] args) throws GraphRunnerException {
        // Use Resource object for project skills directory
        Resource skillsResource = new ClassPathResource("skills");
        Path userHome = Paths.get(System.getProperty("user.home"));
        String skillPath = userHome.resolve(".agents/skills").toString();
        SkillRegistry registry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(skillPath)
                .projectSkillsDirectory(skillsResource)
                .autoLoad(true)
                .build();
        SkillsAgentHook skilshook = SkillsAgentHook.builder()
                .skillRegistry(registry)
                .build();
        ShellToolAgentHook shellHook = ShellToolAgentHook.builder()
                .shellTool2(ShellTool2.builder(System.getProperty("user.dir")).build())
                .build();

        SmartShellToolAgentHook shellHook2 = SmartShellToolAgentHook.builder()
                   .workspaceRoot(System.getProperty("user.dir"))
                    .autoFixEnabled(true)
                    .tryAlternativeShells(true)
                    .verboseErrors(true)
                       .build();
        FileSystemTools tools2 = FileSystemTools.builder().build();
        DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(System.getenv("AI_DASHSCOPE_API_KEY")).build();
        DashScopeChatOptions defaultOptions=new DashScopeChatOptions();
        defaultOptions.setModel("qwen3-max");
        // Create DashScope ChatModel instance
        ChatModel  chatModel = DashScopeChatModel.builder().dashScopeApi(dashScopeApi)
                .defaultOptions(defaultOptions)
                .build();


        // Build agent
        ReactAgent agent = ReactAgent.builder()
                .name("skills-agent-stream")
                .model(chatModel)
                .saver(new MemorySaver())
                .hooks(List.of(skilshook,shellHook2))
                .methodTools(tools2)
                .build();

        Scanner scanner = new Scanner(System.in);
        List<Message> history = new ArrayList<>();

        System.out.println("=== 智能助手已启动 ===");
        System.out.println("提示：输入 'exit' 或按 Ctrl+C 退出");
        System.out.println();
        while (true) {
            System.out.print("用户: ");
            String input =  scanner.nextLine();

            // 检查退出条件
            if ("exit".equalsIgnoreCase(input.trim()) || "quit".equalsIgnoreCase(input.trim())) {
                System.out.println("再见！");
                break;
            }

            if (input.trim().isEmpty()) {
                continue;
            }

            // 创建用户消息
            UserMessage userMessage = new UserMessage(input);
            history.add(userMessage);

            System.out.print("助手: ");

            // 流式调用
            Flux<Message> stream = agent.streamMessages(userMessage);

            StringBuilder fullResponse = new StringBuilder();

            // 订阅流并实时输出
            stream.doOnNext(response -> {
                var content ="";
                if(response instanceof  ToolResponseMessage ){
                    var toolRes=  ((ToolResponseMessage) response);
                    String toolName = toolRes.getResponses().get(0).name();
                    String rawResponse = toolRes.getResponses().get(0).responseData();
                    String truncatedResponse = truncate(rawResponse, MAX_TOOL_RESPONSE_LENGTH);
                    // 如果有截断，预览部分内容
                    String displayResponse = formatMultiline(truncatedResponse, MAX_LINE_WIDTH);
                    content =  toolName + "(" + displayResponse + ")";
                }
                if (response instanceof  AssistantMessage){
                    content= response.getText();
                }
                if (content != null) {
                    System.out.print(content);

                    fullResponse.append(content);
                }
            }).doOnError(error -> {
                System.err.println("\n流式输出错误: " + error.getMessage());
            }).doOnComplete(() -> {
                System.out.println(); // 换行
                System.out.println();
            }).blockLast(); // 等待流完成

            // 将助手的完整回复添加到历史记录
            AssistantMessage assistantMessage = new AssistantMessage(fullResponse.toString());
            history.add(assistantMessage);
        }
    }
}
