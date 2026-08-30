package dev.vishalverma.mnemo.core.command;

import org.junit.jupiter.api.Test;

class GenericCommandsTest extends CommandTestSupport {

    @Test
    void existsCountsEachArgumentIncludingRepeats() {
        run("SET", "a", "1");

        assertInt(run("EXISTS", "a"), 1);
        assertInt(run("EXISTS", "a", "a"), 2);
        assertInt(run("EXISTS", "a", "missing"), 1);
        assertInt(run("EXISTS", "missing"), 0);
    }

    @Test
    void delReportsHowManyKeysItRemoved() {
        run("MSET", "a", "1", "b", "2");

        assertInt(run("DEL", "a", "b", "missing"), 2);
        assertInt(run("DBSIZE"), 0);
    }

    @Test
    void typeIsNoneForAMissingKey() {
        assertSimple(run("TYPE", "missing"), "none");
    }

    @Test
    void typeIsStringForAStringValue() {
        run("SET", "k", "v");
        assertSimple(run("TYPE", "k"), "string");
    }

    @Test
    void dbSizeCountsLiveKeys() {
        assertInt(run("DBSIZE"), 0);

        run("MSET", "a", "1", "b", "2");
        assertInt(run("DBSIZE"), 2);
    }

    @Test
    void dbSizeExcludesExpiredKeys() {
        run("SET", "temp", "v", "EX", "10");
        run("SET", "perm", "v");
        assertInt(run("DBSIZE"), 2);

        clock.advance(10_000);

        assertInt(run("DBSIZE"), 1);
    }

    @Test
    void flushDbRemovesEverything() {
        run("MSET", "a", "1", "b", "2");

        assertOk(run("FLUSHDB"));
        assertInt(run("DBSIZE"), 0);
    }
}
