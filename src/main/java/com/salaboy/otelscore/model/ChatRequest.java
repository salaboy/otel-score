package com.salaboy.otelscore.model;

public record ChatRequest(String conversationId, String clusterName, String message) {}