package dev.vishalverma.mnemo.protocol;

import dev.vishalverma.mnemo.core.type.Bytes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decoder must never assume a read boundary is a message boundary.
 *
 * <p>On loopback, reads almost always arrive whole, so a decoder that quietly depends on that will
 * pass every hand-written test and then fail over a real network. Splitting a valid command at
 * <em>every</em> index and asserting an identical parse is the only way to rule that out — which is
 * why the ROADMAP calls this test non-negotiable.
 */
class RespFragmentationTest {

    private static final String SET = "*3\r\n$3\r\nSET\r\n$6\r\nuser:1\r\n$3\r\nAda\r\n";
    private static final List<String> SET_PARSED = List.of("SET", "user:1", "Ada");

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Feeds the given chunks in order, draining after each, and returns everything parsed. */
    private static List<List<String>> feedInChunks(byte[] wire, int... splitPoints) {
        RespDecoder decoder = new RespDecoder();
        List<List<String>> commands = new ArrayList<>();

        int start = 0;
        for (int end : append(splitPoints, wire.length)) {
            decoder.feed(wire, start, end - start);
            start = end;

            List<Bytes> command;
            while ((command = decoder.next()) != null) {
                commands.add(command.stream().map(Bytes::toString).toList());
            }
        }
        return commands;
    }

    private static int[] append(int[] values, int extra) {
        int[] result = java.util.Arrays.copyOf(values, values.length + 1);
        result[values.length] = extra;
        return result;
    }

    /** Every two-chunk split of a full command must parse identically. */
    @Test
    void parsesIdenticallyForEveryTwoChunkSplit() {
        byte[] wire = ascii(SET);

        for (int split = 0; split <= wire.length; split++) {
            assertThat(feedInChunks(wire, split))
                .as("split after byte %d of %d", split, wire.length)
                .containsExactly(SET_PARSED);
        }
    }

    /**
     * And every three-chunk split. This is O(n²) in the command length, so it runs against a short
     * command — the two-chunk case above already covers the longer one, and the combinations that
     * matter here are the ones that straddle two boundaries at once.
     */
    @Test
    void parsesIdenticallyForEveryThreeChunkSplit() {
        byte[] wire = ascii("*1\r\n$4\r\nPING\r\n");
        List<String> expected = List.of("PING");

        for (int first = 0; first <= wire.length; first++) {
            for (int second = first; second <= wire.length; second++) {
                assertThat(feedInChunks(wire, first, second))
                    .as("splits after bytes %d and %d of %d", first, second, wire.length)
                    .containsExactly(expected);
            }
        }
    }

    /** Byte-at-a-time is the pathological case and the one most likely to expose a stale index. */
    @Test
    void parsesWhenFedOneByteAtATime() {
        byte[] wire = ascii(SET);
        int[] everyIndex = new int[wire.length];
        for (int i = 0; i < wire.length; i++) {
            everyIndex[i] = i;
        }

        assertThat(feedInChunks(wire, everyIndex)).containsExactly(SET_PARSED);
    }

    /** Fragmentation must also hold across a pipelined batch, not just a single command. */
    @Test
    void parsesAPipelinedPairAcrossEverySplit() {
        byte[] wire = ascii("*1\r\n$4\r\nPING\r\n*2\r\n$3\r\nGET\r\n$1\r\nk\r\n");
        List<List<String>> expected = List.of(List.of("PING"), List.of("GET", "k"));

        for (int split = 0; split <= wire.length; split++) {
            assertThat(feedInChunks(wire, split))
                .as("split after byte %d of %d", split, wire.length)
                .isEqualTo(expected);
        }
    }

    /** An inline command arrives fragmented too — a slow telnet typist, or a tiny MTU. */
    @Test
    void parsesInlineCommandsAcrossEverySplit() {
        byte[] wire = ascii("SET foo bar\r\n");
        List<String> expected = List.of("SET", "foo", "bar");

        for (int split = 0; split <= wire.length; split++) {
            assertThat(feedInChunks(wire, split))
                .as("split after byte %d of %d", split, wire.length)
                .containsExactly(expected);
        }
    }
}
