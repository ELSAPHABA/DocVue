package io.dockvue.realtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import reactor.test.StepVerifier;

class StatusControllerTest {
    @Test
    void identifiesService() {
        StepVerifier.create(new StatusController().status())
            .assertNext(s -> assertThat(s.service()).isEqualTo("realtime-api"))
            .verifyComplete();
    }
}
