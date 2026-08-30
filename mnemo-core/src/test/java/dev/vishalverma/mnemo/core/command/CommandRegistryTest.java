package dev.vishalverma.mnemo.core.command;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants over the whole registry rather than any one command — cheap to run, and they catch
 * a bad registration the moment it is added rather than when someone happens to call it.
 */
class CommandRegistryTest extends CommandTestSupport {

    private final CommandRegistry registry = CommandRegistry.standard();

    @Test
    void registersCommands() {
        assertThat(registry.all()).isNotEmpty();
    }

    @Test
    void everyNameIsUppercaseAndLookedUpByIt() {
        for (Command command : registry.all()) {
            assertThat(command.name())
                .isEqualTo(command.name().toUpperCase(Locale.ROOT));
            assertThat(registry.lookup(command.name())).isSameAs(command);
        }
    }

    @Test
    void everyArityAllowsAtLeastTheCommandNameItself() {
        for (Command command : registry.all()) {
            assertThat(command.arity().min())
                .as("%s minimum arity", command.name())
                .isGreaterThanOrEqualTo(1);
            assertThat(command.arity().max())
                .as("%s maximum arity", command.name())
                .isGreaterThanOrEqualTo(command.arity().min());
        }
    }

    @Test
    void everyFirstKeyIsWithinTheCommandsOwnArguments() {
        for (Command command : registry.all()) {
            int firstKey = command.firstKey();
            assertThat(firstKey).as("%s firstKey", command.name()).isGreaterThanOrEqualTo(0);
            if (firstKey > 0) {
                // A key-taking command must actually require enough arguments to have that key.
                assertThat(firstKey)
                    .as("%s declares a key it cannot always receive", command.name())
                    .isLessThan(command.arity().min());
            }
        }
    }

    @Test
    void everyCommandDeclaresAtLeastOneFlag() {
        for (Command command : registry.all()) {
            assertThat(command.flags()).as("%s flags", command.name()).isNotEmpty();
        }
    }

    @Test
    void unknownCommandLooksUpAsNull() {
        assertThat(registry.lookup("NOSUCHCOMMAND")).isNull();
    }

    // ------------------------------------------------------------- dispatch

    @Test
    void dispatchReportsAnUnknownCommand() {
        assertError(run("NOSUCHCOMMAND"), "ERR", "unknown command 'NOSUCHCOMMAND'");
    }

    @Test
    void dispatchReportsTooFewArguments() {
        assertError(run("GET"), "ERR", "wrong number of arguments for 'get' command");
    }

    @Test
    void dispatchReportsTooManyArguments() {
        assertError(run("GET", "a", "b"), "ERR", "wrong number of arguments for 'get' command");
    }

    @Test
    void dispatchIsCaseInsensitive() {
        assertOk(run("sEt", "k", "v"));
        assertBulk(run("GeT", "k"), "v");
    }

    @Test
    void dispatchOfNothingIsAnErrorRatherThanACrash() {
        assertError(engine.dispatch(java.util.List.of()), "ERR", "empty command");
    }
}
