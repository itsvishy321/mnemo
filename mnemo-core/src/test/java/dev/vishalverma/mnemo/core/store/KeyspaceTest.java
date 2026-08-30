package dev.vishalverma.mnemo.core.store;

import dev.vishalverma.mnemo.core.type.Bytes;
import dev.vishalverma.mnemo.core.type.ListValue;
import dev.vishalverma.mnemo.core.type.StringValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeyspaceTest {

    private final ManualClock clock = new ManualClock();
    private Keyspace keyspace;

    private static final Bytes KEY = Bytes.of("k");

    @BeforeEach
    void setUp() {
        keyspace = new Keyspace(clock);
    }

    private static StringValue value(String s) {
        return new StringValue(Bytes.of(s));
    }

    @Test
    void storesAndRetrieves() {
        keyspace.set(KEY, value("v"));

        assertThat(keyspace.get(KEY)).isEqualTo(value("v"));
        assertThat(keyspace.exists(KEY)).isTrue();
        assertThat(keyspace.size()).isEqualTo(1);
    }

    @Test
    void missingKeyReadsAsAbsentRatherThanFailing() {
        assertThat(keyspace.get(KEY)).isNull();
        assertThat(keyspace.exists(KEY)).isFalse();
        assertThat(keyspace.type(KEY)).isEqualTo("none");
        assertThat(keyspace.size()).isZero();
    }

    @Test
    void deleteReportsWhetherItRemovedAnything() {
        keyspace.set(KEY, value("v"));

        assertThat(keyspace.delete(KEY)).isTrue();
        assertThat(keyspace.delete(KEY)).isFalse();
    }

    @Test
    void typeReportsTheStoredKind() {
        keyspace.set(KEY, value("v"));
        assertThat(keyspace.type(KEY)).isEqualTo("string");

        keyspace.set(KEY, new ListValue(List.of(Bytes.of("a"))));
        assertThat(keyspace.type(KEY)).isEqualTo("list");
    }

    @Test
    void expiredKeyReadsAsAbsentOnceTheDeadlinePasses() {
        keyspace.setWithDeadline(KEY, value("v"), clock.nowMs() + 1000);

        clock.advance(999);
        assertThat(keyspace.get(KEY)).isEqualTo(value("v"));

        clock.advance(1);   // deadline is inclusive: expireAtMs <= now means expired
        assertThat(keyspace.get(KEY)).isNull();
        assertThat(keyspace.exists(KEY)).isFalse();
    }

    /** Lazy expiry must actually remove the entry, not merely hide it. */
    @Test
    void expiredKeyIsReclaimedNotJustHidden() {
        keyspace.setWithDeadline(KEY, value("v"), clock.nowMs() + 1000);
        assertThat(keyspace.size()).isEqualTo(1);

        clock.advance(1001);

        assertThat(keyspace.size()).isZero();
        assertThat(keyspace.keys()).isEmpty();
    }

    @Test
    void plainSetClearsAnExistingTtl() {
        keyspace.setWithDeadline(KEY, value("v"), clock.nowMs() + 1000);

        keyspace.set(KEY, value("w"));
        clock.advance(5000);

        assertThat(keyspace.get(KEY)).isEqualTo(value("w"));
    }

    @Test
    void setKeepTtlPreservesAnExistingTtl() {
        keyspace.setWithDeadline(KEY, value("v"), clock.nowMs() + 1000);

        keyspace.setKeepTtl(KEY, value("w"));

        clock.advance(999);
        assertThat(keyspace.get(KEY)).isEqualTo(value("w"));
        clock.advance(1);
        assertThat(keyspace.get(KEY)).isNull();
    }

    @Test
    void setKeepTtlOnAMissingKeyCreatesItWithoutTtl() {
        keyspace.setKeepTtl(KEY, value("v"));

        clock.advance(Long.MAX_VALUE / 2);

        assertThat(keyspace.get(KEY)).isEqualTo(value("v"));
    }

    @Test
    void flushRemovesEverything() {
        keyspace.set(Bytes.of("a"), value("1"));
        keyspace.set(Bytes.of("b"), value("2"));

        keyspace.flush();

        assertThat(keyspace.size()).isZero();
    }
}
