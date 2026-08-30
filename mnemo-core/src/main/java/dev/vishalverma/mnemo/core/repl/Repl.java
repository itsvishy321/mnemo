package dev.vishalverma.mnemo.core.repl;

import dev.vishalverma.mnemo.core.Engine;
import dev.vishalverma.mnemo.core.command.Reply;
import dev.vishalverma.mnemo.core.type.Bytes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * A stdin REPL — the engine's only front end until the RESP server arrives in M2.
 *
 * <p>Lives in {@code mnemo-core} because it needs nothing but the JDK, which means the engine can
 * be demonstrated with no framework, no network, and no other module. {@code System.in} and
 * {@code System.out} are {@code java.*}, so the module stays within the pure-JDK rule.
 */
public final class Repl {

    private static final String PROMPT = "mnemo> ";

    private Repl() {
    }

    public static void main(String[] args) throws IOException {
        try (Reader in = new InputStreamReader(System.in, StandardCharsets.UTF_8);
             Writer out = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            run(in, out, new Engine());
        }
    }

    /**
     * The loop, separated from {@link #main} so tests can drive it from an in-memory script.
     * That turns "the REPL round-trips every implemented command" from a manual demo into an
     * assertion CI can make.
     */
    static void run(Reader in, Writer out, Engine engine) throws IOException {
        BufferedReader reader = new BufferedReader(in);
        String line;

        out.write(PROMPT);
        out.flush();
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                if (isExit(trimmed)) {
                    break;
                }
                out.write(evaluate(engine, trimmed));
                out.write('\n');
            }
            out.write(PROMPT);
            out.flush();
        }
        out.write('\n');
        out.flush();
    }

    private static boolean isExit(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.equals("EXIT") || upper.equals("QUIT");
    }

    private static String evaluate(Engine engine, String line) {
        List<Bytes> args;
        try {
            args = InlineParser.parse(line);
        } catch (InlineParser.ParseException e) {
            return "(error) ERR " + e.getMessage();
        }
        if (args.isEmpty()) {
            return "";
        }
        Reply reply = engine.dispatch(args);   // total: never throws
        return ReplyPrinter.render(reply);
    }
}
