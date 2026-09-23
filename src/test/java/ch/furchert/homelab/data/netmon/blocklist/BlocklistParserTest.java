package ch.furchert.homelab.data.netmon.blocklist;

import ch.furchert.homelab.data.netmon.ip.Cidr;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shapes verified against the live lists on 2026-09-23 (worklog §1). */
class BlocklistParserTest {

    private final BlocklistParser parser = new BlocklistParser(JsonMapper.builder().build());

    static final String SPAMHAUS = """
            {"cidr":"1.10.16.0/20","sblid":"SBL256894","rir":"apnic"}
            {"cidr":"1.19.0.0/16","sblid":"SBL434604","rir":"apnic"}
            {"cidr":"1.19.0.0/16","sblid":"SBL434604","rir":"apnic"}
            {"type":"metadata","timestamp":1790072042,"size":104461,"records":2,"copyright":"(c) 2026 The Spamhaus Project SLU","terms":"https://www.spamhaus.org/drop/terms/"}
            """;

    static final String FIREHOL = """
            #
            # firehol_level1
            #
            0.0.0.0/8
            1.10.16.0/20
            10.0.0.0/8
            100.64.0.0/10
            127.0.0.0/8
            172.16.0.0/12
            192.168.0.0/16
            198.51.100.7
            203.0.113.0/24 # trailing comment
            224.0.0.0/3
            """;

    @Test
    void spamhausNdjsonSkipsMetadataAndDuplicates() {
        assertThat(parser.parse(BlocklistList.SPAMHAUS_DROP_V4, bytes(SPAMHAUS)))
                .containsExactly(Cidr.of("1.10.16.0/20"), Cidr.of("1.19.0.0/16"));
    }

    @Test
    void fireholDropsPrivateAndBogonRanges() {
        assertThat(parser.parse(BlocklistList.FIREHOL_LEVEL1, bytes(FIREHOL)))
                .containsExactly(Cidr.of("1.10.16.0/20"), Cidr.of("198.51.100.7/32"), Cidr.of("203.0.113.0/24"));
    }

    @Test
    void malformedLineFailsTheWholeParse() {
        assertThatThrownBy(() -> parser.parse(BlocklistList.FIREHOL_LEVEL1, bytes("1.2.3.0/24\nnot-a-cidr\n")))
                .isInstanceOf(BlocklistParser.BlocklistParseException.class)
                .hasMessage("line 2 is not an IP or CIDR");
        assertThatThrownBy(() -> parser.parse(BlocklistList.SPAMHAUS_DROP_V4, bytes("{\"cidr\":\"1.2.3.0/24\"}\n{\"cidr\":")))
                .isInstanceOf(BlocklistParser.BlocklistParseException.class)
                .hasMessage("line 2 is not JSON");
        assertThatThrownBy(() -> parser.parse(BlocklistList.SPAMHAUS_DROP_V4, bytes("{\"sblid\":\"x\"}")))
                .isInstanceOf(BlocklistParser.BlocklistParseException.class);
    }

    @Test
    void emptyBodyYieldsNoEntries() {
        assertThat(parser.parse(BlocklistList.FIREHOL_LEVEL1, bytes("# only comments\n"))).isEmpty();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
