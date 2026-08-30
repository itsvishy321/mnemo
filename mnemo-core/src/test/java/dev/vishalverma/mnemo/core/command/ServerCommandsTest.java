package dev.vishalverma.mnemo.core.command;

import dev.vishalverma.mnemo.core.Mnemo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The connection/introspection family that lets a real client hold a session. */
class ServerCommandsTest extends CommandTestSupport {

    @Test
    void pingWithoutArgumentsPongs() {
        assertSimple(run("PING"), "PONG");
    }

    @Test
    void pingWithAnArgumentEchoesIt() {
        assertBulk(run("PING", "hello"), "hello");
    }

    @Test
    void echoReturnsItsArgument() {
        assertBulk(run("ECHO", "hello"), "hello");
    }

    @Test
    void quitAcknowledges() {
        // Closing is the front end's job; the command itself only answers.
        assertOk(run("QUIT"));
    }

    @Test
    void selectAcceptsDatabaseZero() {
        assertOk(run("SELECT", "0"));
    }

    @Test
    void selectRejectsAnyOtherDatabase() {
        assertError(run("SELECT", "1"), "ERR", "DB index is out of range");
    }

    @Test
    void selectRejectsANonNumericIndex() {
        assertError(run("SELECT", "abc"), "ERR", "value is not an integer or out of range");
    }

    // ------------------------------------------------------------------ INFO

    @Test
    void infoReportsTheServerSection() {
        String info = bulkText(run("INFO"));

        assertThat(info).contains("# Server");
        assertThat(info).contains("mnemo_version:" + Mnemo.VERSION);
    }

    @Test
    void infoCanBeNarrowedToASection() {
        assertThat(bulkText(run("INFO", "server"))).contains("# Server").doesNotContain("# Keyspace");
        assertThat(bulkText(run("INFO", "keyspace"))).contains("# Keyspace").doesNotContain("# Server");
    }

    @Test
    void infoReportsKeyCountOnlyWhenThereAreKeys() {
        assertThat(bulkText(run("INFO", "keyspace"))).doesNotContain("db0:");

        run("SET", "k", "v");

        assertThat(bulkText(run("INFO", "keyspace"))).contains("db0:keys=1");
    }

    /** Every line must be field:value under a header — clients parse it by splitting on colons. */
    @Test
    void infoUsesCrLfSeparatedFieldValueLines() {
        String info = bulkText(run("INFO", "server"));

        assertThat(info).startsWith("# Server\r\n");
        for (String line : info.split("\r\n")) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                assertThat(line).as("line %s", line).contains(":");
            }
        }
    }

    // --------------------------------------------------------------- COMMAND

    @Test
    void commandDescribesEveryRegisteredCommand() {
        Reply reply = run("COMMAND");

        assertThat(reply).isInstanceOf(Reply.Arr.class);
        assertThat(((Reply.Arr) reply).items())
            .hasSize(CommandRegistry.standard().all().size());
    }

    @Test
    void commandCountMatchesTheRegistry() {
        assertInt(run("COMMAND", "COUNT"), CommandRegistry.standard().all().size());
    }

    @Test
    void commandInfoDescribesNamedCommands() {
        Reply reply = run("COMMAND", "INFO", "get");

        assertThat(reply).isInstanceOf(Reply.Arr.class);
        Reply entry = ((Reply.Arr) reply).items().get(0);
        assertThat(entry).isInstanceOf(Reply.Arr.class);
        assertThat(((Reply.Arr) entry).items().get(0)).isEqualTo(new Reply.Bulk(bytes("get")));
    }

    @Test
    void commandInfoReturnsNilForAnUnknownName() {
        Reply reply = run("COMMAND", "INFO", "nosuchcommand");

        assertThat(((Reply.Arr) reply).items()).containsExactly(Reply.NIL);
    }

    /**
     * The compatibility rule that matters most: an unmodelled subcommand must answer with an empty
     * array, never an error. redis-cli issues COMMAND DOCS on connect, and an error there is the
     * documented reason a first compatibility attempt appears to hang.
     */
    @Test
    void commandDocsReturnsAnEmptyArrayRatherThanAnError() {
        assertThat(run("COMMAND", "DOCS")).isEqualTo(new Reply.Arr(java.util.List.of()));
    }

    @Test
    void unknownCommandSubcommandAlsoReturnsAnEmptyArray() {
        assertThat(run("COMMAND", "NOSUCHSUBCOMMAND")).isEqualTo(new Reply.Arr(java.util.List.of()));
    }

    /** Redis packs arity into one int: positive is exact, negative means "at least". */
    @Test
    void commandEncodesArityTheWayRedisDoes() {
        Reply get = ((Reply.Arr) run("COMMAND", "INFO", "get")).items().get(0);
        Reply del = ((Reply.Arr) run("COMMAND", "INFO", "del")).items().get(0);

        assertThat(((Reply.Arr) get).items().get(1)).isEqualTo(new Reply.Int(2));
        assertThat(((Reply.Arr) del).items().get(1)).isEqualTo(new Reply.Int(-2));
    }

    private static String bulkText(Reply reply) {
        assertThat(reply).isInstanceOf(Reply.Bulk.class);
        return ((Reply.Bulk) reply).payload().toString();
    }
}
