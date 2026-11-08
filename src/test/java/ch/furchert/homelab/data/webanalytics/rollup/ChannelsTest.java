package ch.furchert.homelab.data.webanalytics.rollup;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/100 §8.4, first match wins. */
class ChannelsTest {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "true,  chatgpt.com,      linkedin, campaign",
            "true,  chatgpt.com,      -,        ai",
            "true,  claude.ai,        -,        ai",
            "true,  google.com,       -,        search",
            "true,  google.ch,        -,        search",
            "true,  google.co.uk,     -,        search",
            "true,  duckduckgo.com,   -,        search",
            "true,  googleblog.com,   -,        referral",
            "true,  linkedin.com,     -,        social",
            "true,  t.co,             -,        social",
            "true,  example.org,      -,        referral",
            "true,  -,                -,        direct",
            "false, -,                -,        internal"
    })
    void classifiesInSpecOrder(boolean landing, String referrer, String utmSource, String expected) {
        assertThat(Channels.classify(landing, referrer, utmSource)).isEqualTo(expected);
    }
}
