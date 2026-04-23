package com.salaboy.otelscore.traces;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.stereotype.Component;

/**
 * Logs tool call name, arguments, and result at INFO level.
 *
 * Spring AI's built-in DEBUG logs only show "Executing tool call: Bash" without
 * revealing what was actually sent or returned. This handler surfaces that
 * information so evaluation progress is visible in the application logs.
 */
@Component
public class ToolCallLoggingObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger log = LoggerFactory.getLogger("otel-score.tools");
    private static final int MAX_LOG_LENGTH = 1000;

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        var ctx = (ToolCallingObservationContext) context;
        log.info("▶ Tool call: {} — args: {}",
                ctx.getToolDefinition().name(),
                truncate(ctx.getToolCallArguments()));
    }

    @Override
    public void onStop(Observation.Context context) {
        var ctx = (ToolCallingObservationContext) context;
        log.info("✓ Tool result: {} — result: {}",
                ctx.getToolDefinition().name(),
                truncate(ctx.getToolCallResult()));
    }

    @Override
    public void onError(Observation.Context context) {
        var ctx = (ToolCallingObservationContext) context;
        log.error("✗ Tool failed: {} — error: {}",
                ctx.getToolDefinition().name(),
                context.getError() != null ? context.getError().getMessage() : "unknown");
    }

    private static String truncate(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.length() <= MAX_LOG_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOG_LENGTH) + "... [truncated, " + value.length() + " chars total]";
    }
}
