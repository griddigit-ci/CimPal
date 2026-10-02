package eu.griddigit.cimpal.main.application.datagenerator;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Passes an RDF/XML stream through unchanged, noting the value of every {@code rdf:about}
 * attribute on the way.
 * <p>
 * CIM XML says with the attribute whether a file defines an object ({@code rdf:ID}) or only refers
 * to one another dataset defines ({@code rdf:about}) - an SSH to its EQ's equipment, a Network Code
 * dataset to the grid model's. Parsing loses that: both come out as the same URI. The wizard needs
 * it to write the objects back the way they came, and to leave the ids of the referenced ones alone
 * when regenerating. Reading the attribute off the stream the parser is already consuming costs no
 * second pass and no buffering of the file.
 */
public final class RdfAboutScanner extends FilterInputStream {

    private static final byte[] NAME = "rdf:about".getBytes(StandardCharsets.US_ASCII);

    private enum State { NAME, BEFORE_EQUALS, BEFORE_QUOTE, VALUE }

    private final Set<String> values = new HashSet<>();
    private final ByteArrayOutputStream value = new ByteArrayOutputStream();
    private State state = State.NAME;
    private int matched;
    private int quote;

    public RdfAboutScanner(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            scan(b);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        for (int i = 0; i < count; i++) {
            scan(buffer[offset + i] & 0xFF);
        }
        return count;
    }

    @Override
    public long skip(long n) throws IOException {
        // Skipped bytes are not seen, so fall back to reading them through scan().
        long skipped = 0;
        while (skipped < n && read() >= 0) {
            skipped++;
        }
        return skipped;
    }

    private void scan(int b) {
        switch (state) {
            case NAME -> {
                if (b == NAME[matched]) {
                    matched++;
                    if (matched == NAME.length) {
                        state = State.BEFORE_EQUALS;
                        matched = 0;
                    }
                } else {
                    matched = b == NAME[0] ? 1 : 0;
                }
            }
            case BEFORE_EQUALS -> state = b == '=' ? State.BEFORE_QUOTE : isSpace(b) ? State.BEFORE_EQUALS : State.NAME;
            case BEFORE_QUOTE -> {
                if (b == '"' || b == '\'') {
                    quote = b;
                    state = State.VALUE;
                } else if (!isSpace(b)) {
                    state = State.NAME;
                }
            }
            case VALUE -> {
                if (b == quote) {
                    values.add(value.toString(StandardCharsets.UTF_8));
                    value.reset();
                    state = State.NAME;
                } else {
                    value.write(b);
                }
            }
        }
    }

    private static boolean isSpace(int b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    /**
     * The subjects of {@code model} - parsed from this stream - that the file wrote with
     * {@code rdf:about}. A same-document value ({@code #_3a3b...}) is matched on the fragment, so it
     * does not matter which base the parser resolved it against.
     */
    public Set<Resource> aboutSubjects(Model model) {
        Set<Resource> about = new HashSet<>();
        for (Resource subject : model.listSubjectsWithProperty(RDF.type).toList()) {
            if (!subject.isURIResource()) {
                continue;
            }
            String uri = subject.getURI();
            int hash = uri.lastIndexOf('#');
            if (values.contains(uri) || (hash >= 0 && values.contains(uri.substring(hash)))) {
                about.add(subject);
            }
        }
        return about;
    }

    /** The URI subjects of {@code model} the file wrote with {@code rdf:ID}: all the rest. */
    public Set<Resource> idSubjects(Model model, Set<Resource> aboutSubjects) {
        Set<Resource> ids = new HashSet<>();
        for (Resource subject : model.listSubjectsWithProperty(RDF.type).toList()) {
            if (subject.isURIResource() && !aboutSubjects.contains(subject)) {
                ids.add(subject);
            }
        }
        return ids;
    }
}
