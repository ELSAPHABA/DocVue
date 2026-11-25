package io.dockvue.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusControllerTest {
    @Test
    void identifiesService() {
        assertThat(new StatusController().status().service()).isEqualTo("core-api");
    }
}
