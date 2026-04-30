package com.salaboy.otelscore.model;

public record ChatRequest(String conversationId, String clusterName,
                          String projectName, String projectUrl,
                          String projectDir,
                          String message) {}