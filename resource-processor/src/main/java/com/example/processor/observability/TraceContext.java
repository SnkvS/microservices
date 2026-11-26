package com.example.processor.observability;

import org.slf4j.MDC;
import java.util.UUID;
import java.util.regex.Pattern;

/** Request/message correlation only; scopes never retain a previous request's ID. */
public final class TraceContext {
    public static final String HEADER = "X-Trace-ID";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private TraceContext() { }

    public static String resolve(Object value) {
        return value instanceof String id && VALID_ID.matcher(id).matches()
                ? id : UUID.randomUUID().toString().replace("-", "");
    }

    public static String currentOrCreate() { return resolve(MDC.get(MDC_KEY)); }

    public static Scope open(Object value) {
        String previous = MDC.get(MDC_KEY);
        String id = resolve(value);
        MDC.put(MDC_KEY, id);
        return new Scope(id, previous);
    }

    public record Scope(String id, String previous) implements AutoCloseable {
        @Override public void close() {
            if (previous == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, previous);
        }
    }
}
