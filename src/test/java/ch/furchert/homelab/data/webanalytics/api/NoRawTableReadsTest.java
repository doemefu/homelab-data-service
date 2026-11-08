package ch.furchert.homelab.data.webanalytics.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/100 §8.1 "aggregates only": every table the read-API package names is an aggregate table,
 * {@code rollup_day} or {@code daily_enquiries}; never {@code webanalytics.events}. Source scan of
 * {@code src/main/java/.../webanalytics/api}.
 */
class NoRawTableReadsTest {

    static final Path API = Path.of("src/main/java/ch/furchert/homelab/data/webanalytics/api");
    static final Set<String> ALLOWED = Set.of("rollup_day", "daily_visits", "daily_visitors", "monthly_visitors",
            "daily_page", "daily_section", "daily_click", "daily_source", "daily_conversion", "daily_enquiries");
    /** A schema-qualified table name in code (not a Java package such as {@code ...data.webanalytics.api}). */
    static final Pattern TABLE = Pattern.compile("(?<![\\w.])webanalytics\\.([a-z_]+)");
    static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    void readApiReferencesOnlyAggregateTables() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(API)) {
            sources = files.filter(f -> f.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        int references = 0;
        for (Path source : sources) {
            String code = COMMENTS.matcher(Files.readString(source)).replaceAll("");
            assertThat(code).as(source.toString()).doesNotContainPattern("(?i)\\bFROM\\s+events\\b");
            Matcher m = TABLE.matcher(code);
            while (m.find()) {
                references++;
                assertThat(ALLOWED).as(source + " references webanalytics." + m.group(1)).contains(m.group(1));
            }
        }
        assertThat(references).isPositive();
    }
}
