package com.salaboy.otelscore.config;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.ShellTools;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
public class AgentConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder,
                          ResourceLoader resourceLoader) {
        return builder
                .defaultSystem("""
                        You are an expert in OpenTelemetry (OTel) project evaluation.
                        You help users assess how well their projects implement OpenTelemetry
                        instrumentation across traces, metrics, and logs.
                        Use the available tools and skills to evaluate projects and provide
                        detailed scoring with actionable recommendations.
                        
                        The flow of this agent must follow:
                        1) install CNCF project using the skill called "install-cncf-project"
                        2) evaluate the project using the skill called "evaluate-otel-maturity"
                        3) provide a final report using the skill called "generate-otel-report"
                        
                        When using a skill or a tool always notify the user about the action but sending
                        regular messages with the progress of the evaluation.
                        
                        The evaluation must finish with a final report but also sending a message to the user about the
                        steps that were taken to complete the evaluation.
                        """)
                .defaultToolCallbacks(SkillsTool.builder()
                        .addSkillsResource(resourceLoader.getResource("classpath:.claude/skills"))
                        .build())
                .defaultTools(FileSystemTools.builder().build())
                .defaultTools(ShellTools.builder().build())
                .build();
    }
}
