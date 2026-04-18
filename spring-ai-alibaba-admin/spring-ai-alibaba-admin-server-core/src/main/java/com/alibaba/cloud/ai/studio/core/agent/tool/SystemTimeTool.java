package com.alibaba.cloud.ai.studio.core.agent.tool;

import com.alibaba.cloud.ai.studio.runtime.domain.chat.ToolCallType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.BiFunction;

public class SystemTimeTool implements BiFunction<SystemTimeTool.TimeRequest, ToolContext, String> {

    @Override
    public String apply(TimeRequest request, ToolContext toolContext) {
        try {
            // 获取指定时区或默认时区
            ZoneId zoneId = request.timezone != null ? ZoneId.of(request.timezone) : ZoneId.systemDefault();
            LocalDateTime now = LocalDateTime.now(zoneId);

            // 根据格式参数返回不同格式的时间
            String format = request.format != null ? request.format : "yyyy-MM-dd HH:mm:ss";
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);

            return now.format(formatter);
        } catch (Exception e) {
            return "获取时间失败: " + e.getMessage();
        }
    }

    /**
     * 创建 AgentToolCallback
     */
    public static AgentToolCallback createCallbackAgent() {
        ToolCallback delegate = FunctionToolCallback.builder("get_system_time", new SystemTimeTool())
                .description("获取当前系统时间和日期，支持指定格式和时区,当需要获取目前日期或者当前时间的时候使用")
                .inputType(TimeRequest.class)
                .build();
        return  AgentToolCallbackAdapter.createCallback(delegate, "get_system_time");
    }

    /**
     * 创建 AgentToolCallback
     */
    public static ToolCallback createCallback() {
        ToolCallback delegate = FunctionToolCallback.builder("get_system_time", new SystemTimeTool())
                .description("获取当前系统时间和日期，支持指定格式和时区,当需要获取目前日期或者当前时间的时候使用")
                .inputType(TimeRequest.class)
                .build();
        return delegate;
    }

    /**
     * 请求参数
     */
    public static class TimeRequest {

        @JsonProperty("format")
        @JsonPropertyDescription("日期时间格式，如 'yyyy-MM-dd HH:mm:ss'、'yyyy-MM-dd'、'HH:mm:ss'，不传则使用默认格式")
        public String format;

        @JsonProperty("timezone")
        @JsonPropertyDescription("时区，如 'Asia/Shanghai'、'UTC'，不传则使用系统默认时区")
        public String timezone;

        public TimeRequest() {
        }

        public TimeRequest(String format, String timezone) {
            this.format = format;
            this.timezone = timezone;
        }
    }
}
