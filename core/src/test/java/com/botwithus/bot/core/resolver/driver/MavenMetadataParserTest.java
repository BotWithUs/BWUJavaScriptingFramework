package com.botwithus.bot.core.resolver.driver;

import com.botwithus.bot.core.resolver.driver.MavenMetadataParser.MetadataParseException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MavenMetadataParserTest {

    private static final String SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>my-script</artifactId>
              <versioning>
                <latest>1.3.0-SNAPSHOT</latest>
                <release>1.2.0</release>
                <versions>
                  <version>1.0.0</version>
                  <version>1.1.0</version>
                  <version>1.2.0</version>
                  <version>1.3.0-SNAPSHOT</version>
                </versions>
                <lastUpdated>20251017123000</lastUpdated>
              </versioning>
            </metadata>
            """;

    private final MavenMetadataParser parser = new MavenMetadataParser();

    @Test
    void parsesValidMetadata() throws Exception {
        MavenMetadata md = parse(SAMPLE);
        assertEquals("com.example", md.groupId());
        assertEquals("my-script", md.artifactId());
        assertEquals("1.3.0-SNAPSHOT", md.latest().orElseThrow());
        assertEquals("1.2.0", md.release().orElseThrow());
        assertEquals(4, md.versions().size());
        assertEquals("1.0.0", md.versions().get(0));
        assertEquals("1.3.0-SNAPSHOT", md.versions().get(3));
    }

    @Test
    void bestRelease_prefersReleaseOverLatest() throws Exception {
        MavenMetadata md = parse(SAMPLE);
        assertEquals("1.2.0", md.bestRelease().orElseThrow());
    }

    @Test
    void bestRelease_fallsBackToLatest() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <versioning>
                    <latest>2.0</latest>
                    <versions><version>2.0</version></versions>
                  </versioning>
                </metadata>
                """;
        assertEquals("2.0", parse(xml).bestRelease().orElseThrow());
    }

    @Test
    void bestRelease_fallsBackToLastVersion() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <versioning>
                    <versions>
                      <version>1.0</version>
                      <version>2.0</version>
                    </versions>
                  </versioning>
                </metadata>
                """;
        assertEquals("2.0", parse(xml).bestRelease().orElseThrow());
    }

    @Test
    void rejectsMalformedXml() {
        String bad = "<metadata><groupId>oops";
        assertThrows(MetadataParseException.class, () -> parse(bad));
    }

    @Test
    void rejectsMissingGroupId() {
        String bad = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <artifactId>a</artifactId>
                  <versioning><versions><version>1.0</version></versions></versioning>
                </metadata>
                """;
        assertThrows(MetadataParseException.class, () -> parse(bad));
    }

    @Test
    void emptyVersionsReturnsEmptyList() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>g</groupId>
                  <artifactId>a</artifactId>
                  <versioning>
                    <versions/>
                  </versioning>
                </metadata>
                """;
        MavenMetadata md = parse(xml);
        assertTrue(md.versions().isEmpty());
        assertTrue(md.bestRelease().isEmpty());
    }

    private MavenMetadata parse(String xml) throws Exception {
        try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return parser.parse(in);
        }
    }
}
