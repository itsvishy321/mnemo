package dev.vishalverma.mnemo.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreMarkerTest {

    @Test
    void reportsItsModuleName() {
        assertThat(CoreMarker.MODULE_NAME).isEqualTo("mnemo-core");
    }
}
