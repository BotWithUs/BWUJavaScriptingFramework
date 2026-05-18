package com.botwithus.bot.core.resolver.metadata;

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
public final class MavenMetadataParser {

    private static final String EL_GROUP_ID = "groupId";
    private static final String EL_ARTIFACT_ID = "artifactId";
    private static final String EL_VERSIONING = "versioning";
    private static final String EL_LATEST = "latest";
    private static final String EL_RELEASE = "release";
    private static final String EL_VERSIONS = "versions";
    private static final String EL_VERSION = "version";

    public MavenMetadata parse(Path xmlFile) throws IOException, MetadataParseException {
        Objects.requireNonNull(xmlFile, "xmlFile");
        try (InputStream in = Files.newInputStream(xmlFile)) {
            return parse(in);
        }
    }

    public MavenMetadata parse(InputStream stream) throws MetadataParseException {
        Objects.requireNonNull(stream, "stream");
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);

        String groupId = null;
        String artifactId = null;
        String latest = null;
        String release = null;
        List<String> versions = new ArrayList<>();
        boolean inVersioning = false;
        boolean inVersionsBlock = false;

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(stream);
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = reader.getLocalName();
                        switch (name) {
                            case EL_VERSIONING -> inVersioning = true;
                            case EL_VERSIONS -> {
                                if (inVersioning) {
                                    inVersionsBlock = true;
                                }
                            }
                            case EL_GROUP_ID -> {
                                if (!inVersioning && groupId == null) {
                                    groupId = readText(reader);
                                }
                            }
                            case EL_ARTIFACT_ID -> {
                                if (!inVersioning && artifactId == null) {
                                    artifactId = readText(reader);
                                }
                            }
                            case EL_LATEST -> {
                                if (inVersioning) {
                                    latest = readText(reader);
                                }
                            }
                            case EL_RELEASE -> {
                                if (inVersioning) {
                                    release = readText(reader);
                                }
                            }
                            case EL_VERSION -> {
                                if (inVersionsBlock) {
                                    String v = readText(reader);
                                    if (v != null && !v.isBlank()) {
                                        versions.add(v.trim());
                                    }
                                }
                            }
                            default -> {
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String name = reader.getLocalName();
                        if (EL_VERSIONS.equals(name)) {
                            inVersionsBlock = false;
                        } else if (EL_VERSIONING.equals(name)) {
                            inVersioning = false;
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new MetadataParseException("Malformed maven-metadata.xml: " + e.getMessage(), e);
        }

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
    public static final class MetadataParseException extends Exception {
        public MetadataParseException(String message) {
            super(message);
        }

        public MetadataParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
