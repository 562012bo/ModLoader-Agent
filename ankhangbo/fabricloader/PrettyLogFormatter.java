package ankhangbo.fabricloader;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Replaces java.util.logging's default two-line format:
 * <pre>
 * Aug 18, 2026 1:43:46 PM ankhangbo.fabricloader.FabricLoaderAgent premain
 * INFO: PaperclipTransformer installed - waiting for io.papermc.paperclip.Paperclip to load.
 * </pre>
 * with a single, concise line matching this project's existing console log style:
 * <pre>
 * [12:14:24 INFO]: PaperclipTransformer installed - waiting for io.papermc.paperclip.Paperclip to load.
 * </pre>
 */
public final class PrettyLogFormatter extends Formatter {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public String format(LogRecord record) {
        String time = LocalTime.now().format(TIME_FORMAT);
        String level = record.getLevel().getName();
        String message = formatMessage(record);

        StringBuilder sb = new StringBuilder();
        sb.append('[').append(time).append(' ').append(level).append("]: ").append(message).append('\n');

        if (record.getThrown() != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            record.getThrown().printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw);
        }

        return sb.toString();
    }
}
