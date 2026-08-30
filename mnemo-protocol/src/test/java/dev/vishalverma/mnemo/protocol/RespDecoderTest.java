package dev.vishalverma.mnemo.protocol;

import dev.vishalverma.mnemo.core.type.Bytes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespDecoderTest {

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Feeds everything in one chunk and drains. */
    private static List<List<String>> decodeAll(String wire) {
        return decodeAll(new RespDecoder(), ascii(wire));
    }

    private static List<List<String>> decodeAll(RespDecoder decoder, byte[] data) {
        decoder.feed(data, 0, data.length);
        List<List<String>> commands = new ArrayList<>();
        List<Bytes> command;
        while ((command = decoder.next()) != null) {
            commands.add(command.stream().map(Bytes::toString).toList());
        }
        return commands;
    }

    @Test
    void decodesAMultibulkCommand() {
        assertThat(decodeAll("*3\r\n$3\r\nSET\r\n$6\r\nuser:1\r\n$3\r\nAda\r\n"))
            .containsExactly(List.of("SET", "user:1", "Ada"));
    }

    @Test
    void returnsNullWhenNothingHasArrived() {
        assertThat(new RespDecoder().next()).isNull();
    }

    // ------------------------------------------------------------- bulk edges

    /** An empty bulk is a real, distinct value — not the same as a missing one. */
    @Test
    void emptyBulkIsDistinctFromAbsent() {
        assertThat(decodeAll("*2\r\n$3\r\nGET\r\n$0\r\n\r\n"))
            .containsExactly(List.of("GET", ""));
    }

    /** Payloads are length-prefixed, so CR and LF inside them are ordinary bytes. */
    @Test
    void payloadMayContainCrLf() {
        List<List<String>> commands = decodeAll("*2\r\n$3\r\nSET\r\n$4\r\na\r\nb\r\n");

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).get(1)).isEqualTo("a\r\nb");
    }

    @Test
    void payloadMayContainNulBytes() {
        byte[] wire = ascii("*2\r\n$3\r\nGET\r\n$3\r\na?b\r\n");
        wire[wire.length - 4] = 0;   // replace the '?' with a NUL

        RespDecoder decoder = new RespDecoder();
        decoder.feed(wire, 0, wire.length);
        List<Bytes> command = decoder.next();

        assertThat(command).hasSize(2);
        assertThat(command.get(1).array()).containsExactly('a', 0, 'b');
    }

    @Test
    void emptyArrayYieldsNoCommandRatherThanStalling() {
        // Must consume the input and report "nothing yet" without looping forever.
        assertThat(decodeAll("*0\r\n")).isEmpty();
    }

    @Test
    void emptyArrayIsSkippedButFollowingCommandIsStillParsed() {
        assertThat(decodeAll("*0\r\n*1\r\n$4\r\nPING\r\n"))
            .containsExactly(List.of("PING"));
    }

    // ---------------------------------------------------------------- inline

    @Test
    void decodesAnInlineCommand() {
        assertThat(decodeAll("PING\r\n")).containsExactly(List.of("PING"));
    }

    @Test
    void decodesAnInlineCommandWithArguments() {
        assertThat(decodeAll("SET foo bar\r\n")).containsExactly(List.of("SET", "foo", "bar"));
    }

    @Test
    void acceptsBareLineFeedForInlineCommands() {
        assertThat(decodeAll("PING\n")).containsExactly(List.of("PING"));
    }

    @Test
    void collapsesRepeatedWhitespaceInInlineCommands() {
        assertThat(decodeAll("SET   foo\tbar\r\n")).containsExactly(List.of("SET", "foo", "bar"));
    }

    @Test
    void blankInlineLineYieldsNoCommand() {
        assertThat(decodeAll("\r\n")).isEmpty();
    }

    // ------------------------------------------------------------ pipelining

    @Test
    void parsesSeveralCommandsFromOneBuffer() {
        assertThat(decodeAll("*1\r\n$4\r\nPING\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n"))
            .containsExactly(List.of("PING"), List.of("GET", "k"));
    }

    @Test
    void parsesATenThousandCommandPipelinedBatchInOneBuffer() {
        StringBuilder wire = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            wire.append("*2\r\n$3\r\nGET\r\n$").append(Integer.toString(i).length())
                .append("\r\n").append(i).append("\r\n");
        }

        List<List<String>> commands = decodeAll(wire.toString());

        assertThat(commands).hasSize(10_000);
        assertThat(commands.get(0)).containsExactly("GET", "0");
        assertThat(commands.get(9_999)).containsExactly("GET", "9999");
    }

    // ------------------------------------------------------- malformed input

    @Test
    void rejectsNegativeBulkLength() {
        // $-1 is the null bulk in a REPLY; in a request it is malformed, as Redis also treats it.
        assertThatThrownBy(() -> decodeAll("*1\r\n$-1\r\n"))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("invalid bulk length");
    }

    @Test
    void rejectsAbsurdBulkLengthWithoutAllocating() {
        // The point is that this is refused from the declared length alone. If the decoder tried
        // to allocate first, this test would OOM rather than fail.
        assertThatThrownBy(() -> decodeAll("*1\r\n$999999999999\r\n"))
            .isInstanceOf(ProtocolException.class);
    }

    @Test
    void rejectsOverflowingBulkLength() {
        assertThatThrownBy(() -> decodeAll("*1\r\n$99999999999999999999\r\n"))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("out of range");
    }

    @Test
    void rejectsBulkLengthOverTheConfiguredLimit() {
        RespLimits tiny = new RespLimits(16, 1024, 1024, 4096);

        assertThatThrownBy(() -> decodeAll(new RespDecoder(tiny), ascii("*1\r\n$17\r\n")))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("exceeds limit");
    }

    @Test
    void rejectsMultibulkCountOverTheConfiguredLimit() {
        RespLimits tiny = new RespLimits(16, 4, 1024, 4096);

        assertThatThrownBy(() -> decodeAll(new RespDecoder(tiny), ascii("*5\r\n")))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("multibulk length");
    }

    @Test
    void rejectsBulkNotTerminatedByCrLf() {
        assertThatThrownBy(() -> decodeAll("*1\r\n$1\r\nabc\r\n"))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("not terminated");
    }

    @Test
    void rejectsMalformedLength() {
        assertThatThrownBy(() -> decodeAll("*1\r\n$xy\r\nab\r\n"))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("malformed length");
    }

    @Test
    void rejectsAnUnterminatedLineThatGrowsPastTheLimit() {
        RespLimits tiny = new RespLimits(16, 1024, 8, 4096);

        assertThatThrownBy(() -> decodeAll(new RespDecoder(tiny), ascii("*12345678901234567890")))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("unterminated");
    }

    @Test
    void rejectsInputThatWouldGrowTheBufferPastTheLimit() {
        RespLimits tiny = new RespLimits(16, 1024, 1024, 32);
        RespDecoder decoder = new RespDecoder(tiny);
        byte[] chunk = new byte[64];

        assertThatThrownBy(() -> decoder.feed(chunk, 0, chunk.length))
            .isInstanceOf(ProtocolException.class)
            .hasMessageContaining("buffer would exceed");
    }
}
