package com.botwithus.bot.core.resolver.driver;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * StAX parser for Maven {@code maven-metadata.xml} files. Uses the JDK's
 * {@link XMLInputFactory} so no external XML dependency is needed.
 *
 * <p>The parser is hardened against XXE: {@code IS_SUPPORTING_EXTERNAL_ENTITIES}
 * is disabled, {@code SUPPORT_DTD} is disabled. The format we care about
 * does not use either.</p>
 */
final class MavenMetadataParser {

    private static final String EL_GROUP_ID = "groupId";
    private static final String EL_ARTIFACT_ID = "artifactId";
    private static final String EL_VERSIONING = "versioning";
    private static final String EL_LATEST = "latest";
    private static final String EL_RELEASE = "release";
    private static final String EL_VERSIONS = "versions";
    private static final String EL_VERSION = "version";

    MavenMetadata parse(Path xmlFile) throws IOException, MetadataParseException {
        Objects.requireNonNull(xmlFile, "xmlFile");
        try (InputStream in = Files.newInputStream(xmlFile)) {
            return parse(in);
        }
    }

    MavenMetadata parse(InputStream stream) throws MetadataParseException {
        Objects.requireNonNull(stream, "stream");
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        Accumulator acc = new Accumulator();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(stream);
            try {
                readDocument(reader, acc);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new MetadataParseException("Malformed maven-metadata.xml: " + e.getMessage(), e);
        }

        return acc.toMetadata();
    }

    private static void readDocument(XMLStreamReader reader, Accumulator acc) throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                handleStartElement(reader, acc);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                handleEndElement(reader.getLocalName(), acc);
            }
        }
    }

    private static void handleStartElement(XMLStreamReader reader, Accumulator acc) throws XMLStreamException {
        String name = reader.getLocalName();
        switch (name) {
            case EL_VERSIONING -> acc.inVersioning = true;
            case EL_VERSIONS -> {
                if (acc.inVersioning) {
                    acc.inVersionsBlock = true;
                }
            }
            case EL_GROUP_ID -> {
                if (!acc.inVersioning && acc.groupId == null) {
                    acc.groupId = readText(reader);
                }
            }
            case EL_ARTIFACT_ID -> {
                if (!acc.inVersioning && acc.artifactId == null) {
                    acc.artifactId = readText(reader);
                }
            }
            case EL_LATEST -> {
                if (acc.inVersioning) {
                    acc.latest = readText(reader);
                }
            }
            case EL_RELEASE -> {
                if (acc.inVersioning) {
                    acc.release = readText(reader);
                }
            }
            case EL_VERSION -> {
                if (acc.inVersionsBlock) {
                    String v = readText(reader);
                    if (v != null && !v.isBlank()) {
                        acc.versions.add(v.trim());
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void handleEndElement(String name, Accumulator acc) {
        if (EL_VERSIONS.equals(name)) {
            acc.inVersionsBlock = false;
        } else if (EL_VERSIONING.equals(name)) {
            acc.inVersioning = false;
        }
    }

    /** Mutable scratch state threaded through the StAX pull loop. */
    private static final class Accumulator {
        private String groupId = null;
        private String artifactId = null;
        private String latest = null;
        private String release = null;
        private final List<String> versions = new ArrayList<>();
        private boolean inVersioning = false;
        private boolean inVersionsBlock = false;

        MavenMetadata toMetadata() throws MetadataParseException {
            if (groupId == null || artifactId == null) {
                throw new MetadataParseException("maven-metadata.xml missing groupId or artifactId");
            }
            return new MavenMetadata(
                    groupId,
                    artifactId,
                    Optional.ofNullable(emptyToNull(latest)),
                    Optional.ofNullable(emptyToNull(release)),
                    versions);
        }
    }

    private static String readText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder buf = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                buf.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                while (reader.hasNext() && reader.next() != XMLStreamConstants.END_ELEMENT) {
                }
            }
        }
        return buf.toString().trim();
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Thrown when {@code maven-metadata.xml} cannot be parsed. */
    static final class MetadataParseException extends Exception {
        MetadataParseException(String message) {
            super(message);
        }

        MetadataParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
