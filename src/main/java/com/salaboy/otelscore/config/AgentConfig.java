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
                        
                        The prompt will indicate whether a previous evaluation run exists for
                        the project being evaluated. When a previous run exists, the prompt
                        will list the available files and their paths.

                        The flow of this agent must follow all three phases in order:

                        1) INSTALL the CNCF project:
                           - If the prompt lists an INSTALL-PLAN.md from a previous run, read
                             it with FileSystemTools and use it to install the project directly.
                             Skip the research phase of the "install-cncf-project" skill.
                           - If no INSTALL-PLAN.md is available, run the full
                             "install-cncf-project" skill to research and install the project.

                        2) EVALUATE the project's OTel maturity:
                           - If the prompt lists an EVALUATION.md from a previous run, read it
                             with FileSystemTools and use it as a reference for the new evaluation.
                           - Always run the "evaluate-otel-maturity" skill to produce a fresh
                             evaluation based on the current telemetry data.

                        3) GENERATE the final report using the "generate-otel-report" skill.
                           This step MUST produce a report.html file. The evaluation is NOT
                           complete until report.html has been generated. Always run this step
                           even if previous steps encountered issues.

                        When using a skill or a tool always notify the user about the action but sending
                        regular messages with the progress of the evaluation.

                        The evaluation must finish with the generated report.html file.
                        
                        When the evaluation is finished, a message to the user about the steps that were taken to perform 
                        the evaluation must be sent as the last message to the user. Use ++++ as a separator.
                        """)
                .defaultToolCallbacks(SkillsTool.builder()
                        .addSkillsResource(resourceLoader.getResource("classpath:.claude/skills"))
                        .build())
                .defaultTools(FileSystemTools.builder().build())
                .defaultTools(ShellTools.builder().build())
                .build();
    }
}
