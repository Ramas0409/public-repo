package com.wp.wdp.chargeback.auto.response.util;

import java.util.ArrayList;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/** Every log event of the service while it is open, each with the line the service's pattern prints for it. */
public final class LogCapture implements AutoCloseable {
    public static final String PATTERN = "%5p [corr=%X{correlationId:--} case=%X{caseKey:--}] %logger : %m%n%ex";

    private static final String SERVICE = "com.wp.wdp.chargeback.auto.response";

    private final Logger root;
    private final Level level;
    private final Logger service;
    private final Level serviceLevel;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final PatternLayout layout = new PatternLayout();

    private LogCapture() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        level = root.getLevel();
        layout.setContext(root.getLoggerContext());
        layout.setPattern(PATTERN);
        layout.start();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
        service = root.getLoggerContext().getLogger(SERVICE);
        serviceLevel = service.getLevel();
        service.setLevel(Level.DEBUG);
    }

    public static LogCapture open() {
        return new LogCapture();
    }

    /** A snapshot of the events, taken under the appender's lock so another thread's line cannot break it. */
    public List<ILoggingEvent> events() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    public List<ILoggingEvent> service() {
        List<ILoggingEvent> out = new ArrayList<>();
        for (ILoggingEvent event : events()) {
            if (event.getLoggerName().startsWith(SERVICE)) {
                out.add(event);
            }
        }
        return out;
    }

    public List<ILoggingEvent> withEvent(String name) {
        List<ILoggingEvent> out = new ArrayList<>();
        for (ILoggingEvent event : events()) {
            if ((event.getFormattedMessage() + " ").startsWith("event=" + name + " ")) {
                out.add(event);
            }
        }
        return out;
    }

    public String printed(ILoggingEvent event) {
        return layout.doLayout(event);
    }

    /** Every line the service printed, and every other line at INFO or above, as they print. */
    public String printedAll() {
        StringBuilder out = new StringBuilder();
        for (ILoggingEvent event : events()) {
            if (event.getLoggerName().startsWith(SERVICE)
                    || event.getLevel().isGreaterOrEqual(Level.INFO)) {
                out.append(printed(event));
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(level);
        service.setLevel(serviceLevel);
        layout.stop();
        appender.stop();
    }
}
