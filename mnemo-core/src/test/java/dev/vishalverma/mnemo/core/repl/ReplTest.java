package dev.vishalverma.mnemo.core.repl;

import dev.vishalverma.mnemo.core.Engine;
import dev.vishalverma.mnemo.core.command.Command;
import dev.vishalverma.mnemo.core.command.CommandRegistry;
import dev.vishalverma.mnemo.core.store.ManualClock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ReplTest {

    private final ManualClock clock = new ManualClock();

    /** Feeds a script through the REPL and returns everything it printed. */
    private String replay(String script) throws IOException {
        StringWriter out = new StringWriter();
        Repl.run(new StringReader(script), out, new Engine(clock));
        return out.toString();
    }

    /** Just the reply lines, with the prompts stripped, so assertions read cleanly. */
    private List<String> replies(String script) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : replay(script).split("\n", -1)) {
            String stripped = line.replace("mnemo> ", "").trim();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    @Test
    void roundTripsASetAndGet() throws IOException {
        assertThat(replies("SET k v\nGET k\n")).containsExactly("OK", "\"v\"");
    }

    @Test
    void rendersEachReplyKind() throws IOException {
        List<String> out = replies("""
            SET k v
            GET k
            STRLEN k
            GET missing
            TYPE k
            MGET k missing
            NOSUCHCOMMAND
            """);

        assertThat(out).containsExactly(
            "OK",                 // Simple
            "\"v\"",              // Bulk
            "(integer) 1",        // Int
            "(nil)",              // Nil
            "string",             // Simple
            "1) \"v\"",           // Arr, first element
            "2) (nil)",           // Arr, second element
            "(error) ERR unknown command 'NOSUCHCOMMAND'");   // Err
    }

    @Test
    void ignoresBlankLines() throws IOException {
        assertThat(replies("\n\n   \nSET k v\n")).containsExactly("OK");
    }

    @Test
    void handlesQuotedArgumentsContainingSpaces() throws IOException {
        assertThat(replies("SET k \"hello world\"\nSTRLEN k\n"))
            .containsExactly("OK", "(integer) 11");
    }

    /** The \\xHH escape is the only way to demo binary safety from a terminal. */
    @Test
    void handlesBinaryEscapesAndRendersThemBack() throws IOException {
        assertThat(replies("SET k \"a\\x00b\"\nSTRLEN k\nGET k\n"))
            .containsExactly("OK", "(integer) 3", "\"a\\x00b\"");
    }

    @Test
    void keysContainingNullBytesAreDistinct() throws IOException {
        assertThat(replies("SET \"a\\x00b\" one\nSET ab two\nGET \"a\\x00b\"\nGET ab\nDBSIZE\n"))
            .containsExactly("OK", "OK", "\"one\"", "\"two\"", "(integer) 2");
    }

    @Test
    void reportsUnbalancedQuotesWithoutCrashing() throws IOException {
        assertThat(replies("SET k \"unterminated\n")).containsExactly("(error) ERR unbalanced quotes");
    }

    @Test
    void exitStopsTheLoop() throws IOException {
        assertThat(replies("SET k v\nEXIT\nSET k should-not-run\n")).containsExactly("OK");
    }

    /**
     * M1's acceptance criterion, made executable: every registered command must be reachable
     * through the REPL. A command that dispatches to "unknown command" here is one that was
     * written but never registered — the exact mistake the two-file rule is meant to make hard.
     */
    @Test
    void everyRegisteredCommandIsReachableThroughTheRepl() throws IOException {
        for (Command command : CommandRegistry.standard().all()) {
            String name = command.name();
            List<String> out = replies(name + "\n");

            assertThat(out).hasSize(1);
            assertThat(out.get(0))
                .as("%s should be reachable from the REPL", name)
                .doesNotContain("unknown command");
        }
    }

    /** Names are matched case-insensitively, as redis-cli allows. */
    @Test
    void acceptsLowercaseCommandNames() throws IOException {
        for (Command command : CommandRegistry.standard().all()) {
            String lower = command.name().toLowerCase(Locale.ROOT);
            assertThat(replies(lower + "\n").get(0))
                .as("%s should be reachable in lowercase", lower)
                .doesNotContain("unknown command");
        }
    }
}
