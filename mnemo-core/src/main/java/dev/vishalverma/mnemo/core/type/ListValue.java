package dev.vishalverma.mnemo.core.type;

import java.util.List;

/**
 * A list value — data only, with no commands until M4.
 *
 * <p>It ships in M1 for one reason: M1's acceptance criteria require testing that type errors
 * produce {@code WRONGTYPE}, and with {@link StringValue} as the sole variant nothing can <em>be</em>
 * the wrong type. {@link RedisValue} being sealed also stops a test from supplying a fake. Fifteen
 * lines here make a stated criterion actually verifiable.
 *
 * <p>M4 replaces the backing store with an {@code ArrayDeque} for O(1) push/pop at both ends.
 */
public record ListValue(List<Bytes> items) implements RedisValue {

    public ListValue {
        items = List.copyOf(items);
    }

    @Override
    public String typeName() {
        return "list";
    }

    @Override
    public int sizeBytes() {
        int total = 48;
        for (Bytes item : items) {
            total += item.length() + 48;
        }
        return total;
    }
}
