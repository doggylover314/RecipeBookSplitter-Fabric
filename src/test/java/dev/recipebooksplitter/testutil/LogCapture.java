package dev.recipebooksplitter.testutil;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;

/** Records what a logger logs while open (at DEBUG and above unless another level is given), so tests can assert on log output. */
public final class LogCapture implements AutoCloseable {
    public record Entry(Level level, String message) {}

    private final String loggerName;
    private final Level previousLevel;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AbstractAppender appender = new AbstractAppender("log-capture", null, null, true, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            entries.add(new Entry(event.getLevel(), event.getMessage().getFormattedMessage()));
        }
    };

    public LogCapture(String loggerName) {
        this(loggerName, Level.DEBUG);
    }

    /** @param level the level the logger is set to while open, for example INFO as on a production server */
    public LogCapture(String loggerName, Level level) {
        this.loggerName = loggerName;
        previousLevel = LogManager.getLogger(loggerName).getLevel();
        Configurator.setLevel(loggerName, level);
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        appender.start();
        config.getLoggerConfig(loggerName).addAppender(appender, null, null);
        context.updateLoggers();
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Messages logged at exactly this level. */
    public List<String> messages(Level level) {
        return entries.stream().filter(entry -> entry.level() == level).map(Entry::message).toList();
    }

    @Override
    public void close() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getConfiguration().getLoggerConfig(loggerName).removeAppender(appender.getName());
        appender.stop();
        Configurator.setLevel(loggerName, previousLevel);
    }
}
