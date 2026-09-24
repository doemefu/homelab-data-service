package ch.furchert.homelab.data.netmon.blocklist;

import ch.furchert.homelab.data.netmon.ip.Cidr;
import ch.furchert.homelab.data.netmon.ip.PublicIpFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses a blocklist body completely before anything is written (docs/060 §4.4). A syntax error anywhere
 * fails the whole parse, so a truncated or corrupted download never replaces the current entries.
 * Entries overlapping a non-public range (§4.3) are dropped — FireHOL level1 lists the RFC 1918 and
 * bogon ranges — so LAN addresses are never blocklisted. Duplicates collapse to one entry.
 */
public final class BlocklistParser {

    private final JsonMapper jsonMapper;

    public BlocklistParser(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public List<Cidr> parse(BlocklistList list, byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        return switch (list) {
            case SPAMHAUS_DROP_V4 -> parseSpamhaus(text);
            case FIREHOL_LEVEL1 -> parseNetset(text);
        };
    }

    /** NDJSON: {@code {"cidr":"…","sblid":"…","rir":"…"}} per line, plus a final {@code {"type":"metadata",…}}. */
    private List<Cidr> parseSpamhaus(String text) {
        Set<Cidr> entries = new LinkedHashSet<>();
        int lineNo = 0;
        for (String line : text.split("\n")) {
            lineNo++;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = jsonMapper.readTree(trimmed);
            } catch (JacksonException e) {
                throw new BlocklistParseException("line " + lineNo + " is not JSON");
            }
            if (node.has("type")) {
                continue; // metadata record
            }
            JsonNode cidr = node.path("cidr");
            if (!cidr.isString()) {
                throw new BlocklistParseException("line " + lineNo + " has no cidr");
            }
            add(entries, cidr.asString(), lineNo);
        }
        return List.copyOf(entries);
    }

    /** One IP or CIDR per line; {@code #} starts a comment. */
    private List<Cidr> parseNetset(String text) {
        Set<Cidr> entries = new LinkedHashSet<>();
        int lineNo = 0;
        for (String line : text.split("\n")) {
            lineNo++;
            int hash = line.indexOf('#');
            String value = (hash >= 0 ? line.substring(0, hash) : line).strip();
            if (!value.isEmpty()) {
                add(entries, value, lineNo);
            }
        }
        return List.copyOf(entries);
    }

    private static void add(Set<Cidr> entries, String value, int lineNo) {
        Cidr cidr = Cidr.parse(value)
                .orElseThrow(() -> new BlocklistParseException("line " + lineNo + " is not an IP or CIDR"));
        if (!PublicIpFilter.overlapsNonPublic(cidr)) {
            entries.add(cidr);
        }
    }

    /** The message names a line number only, never list content. */
    public static final class BlocklistParseException extends RuntimeException {
        BlocklistParseException(String message) {
            super(message);
        }
    }
}
