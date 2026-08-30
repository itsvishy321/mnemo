package dev.vishalverma.mnemo.core.type;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper exists so that byte strings behave as values rather than identities. These tests
 * pin exactly that, plus the binary-safety claims the storage layer rests on.
 */
class BytesTest {

    @Test
    void equalContentInDistinctArraysIsEqual() {
        Bytes first = Bytes.wrap(new byte[] {1, 2, 3});
        Bytes second = Bytes.wrap(new byte[] {1, 2, 3});

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void differentContentIsNotEqual() {
        assertThat(Bytes.of("abc")).isNotEqualTo(Bytes.of("abd"));
    }

    @Test
    void worksAsAHashMapKey() {
        Map<Bytes, String> map = new HashMap<>();
        map.put(Bytes.of("key"), "value");

        // A distinct-but-equal instance must find the entry — the whole reason this type exists.
        assertThat(map).containsEntry(Bytes.of("key"), "value");
    }

    @Test
    void isBinarySafeForEmbeddedNulls() {
        byte[] withNull = {'a', 0, 'b'};

        assertThat(Bytes.wrap(withNull).length()).isEqualTo(3);
        assertThat(Bytes.wrap(withNull)).isEqualTo(Bytes.wrap(new byte[] {'a', 0, 'b'}));
        // "a\0b" and "ab" must not collide once the NUL is dropped by a naive String conversion.
        assertThat(Bytes.wrap(withNull)).isNotEqualTo(Bytes.of("ab"));
    }

    @Test
    void preservesInvalidUtf8Exactly() {
        // A lone continuation byte — not valid UTF-8, and must survive a round trip untouched.
        byte[] invalid = {(byte) 0xC3, (byte) 0x28, (byte) 0xFF};

        assertThat(Bytes.wrap(invalid).array()).containsExactly(invalid);
    }

    @Test
    void emptyHashesWithoutError() {
        assertThat(Bytes.EMPTY.length()).isZero();
        assertThat(Bytes.EMPTY.hashCode()).isZero();
        assertThat(Bytes.EMPTY).isEqualTo(Bytes.wrap(new byte[0]));
    }

    @Test
    void copyOfIsInsulatedFromLaterMutation() {
        byte[] source = {1, 2, 3};
        Bytes copied = Bytes.copyOf(source);

        source[0] = 99;

        assertThat(copied.array()).containsExactly(1, 2, 3);
    }

    @Test
    void ofUsesUtf8() {
        assertThat(Bytes.of("é").array()).containsExactly("é".getBytes(StandardCharsets.UTF_8));
    }
}
