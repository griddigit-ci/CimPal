/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EA constraint CSV → .ttl (GUI Restore ttl from constraint csv, CLI {@code csv2shacl}). */
class ConstraintCsvRestoreTest {

    private static final String HEADER = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n@prefix ex: <http://example.org/s#> .";
    private static final String LINE = "ex:Line a sh:NodeShape ;\n    sh:targetClass ex:ACLineSegment .";
    private static final String TERMINAL = "ex:Terminal a sh:NodeShape ;\n    sh:targetClass ex:Terminal .";

    @TempDir
    Path tempDir;

    /** RFC-4180 quoting, as the EA export writes it. */
    private static String q(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private Path csv(String... rows) throws IOException {
        StringBuilder b = new StringBuilder("SourceFile,PropertyShape/NodeShape ID,Payload\n");
        for (String row : rows) b.append(row).append('\n');
        return Files.writeString(tempDir.resolve("in.csv"), b.toString());
    }

    @Test
    void rowsAreGroupedBySourceFileWithTheHeaderFirstAndDuplicatesOnce() throws Exception {
        Path csv = csv(
                "EQ.ttl,ex:Line," + q(LINE),
                "EQ.ttl,__HEADER__," + q(HEADER),
                "EQ.ttl,ex:Line," + q(LINE),
                "SSH.ttl,__HEADER__," + q(HEADER),
                "SSH.ttl,ex:Terminal," + q(TERMINAL));

        ConstraintCsvRestore.Result result = ConstraintCsvRestore.restore(csv, tempDir.resolve("out"));

        assertThat(result.csvRowsRead()).isEqualTo(5);
        assertThat(result.payloadBlocksWritten()).isEqualTo(4);
        assertThat(result.ttlFiles()).extracting(p -> p.getFileName().toString()).containsExactly("EQ.ttl", "SSH.ttl");
        String eq = Files.readString(tempDir.resolve("out/EQ.ttl"));
        assertThat(eq).startsWith("@prefix sh:");
        Model m = RDFDataMgr.loadModel(tempDir.resolve("out/EQ.ttl").toString());
        assertThat(m.size()).isEqualTo(2);
    }

    /** The EA export neutralises formulas with an apostrophe ('@prefix ...); the restore removes it. */
    @Test
    void theExportsFormulaApostropheIsRemoved() throws Exception {
        Path csv = csv("EQ.ttl,__HEADER__," + q("'" + HEADER), "EQ.ttl,ex:Line," + q(LINE));

        ConstraintCsvRestore.restore(csv, tempDir.resolve("out"));

        assertThat(Files.readString(tempDir.resolve("out/EQ.ttl"))).startsWith("@prefix");
    }

    @Test
    void withoutASourceFileTheNameComesFromTheCsv() throws Exception {
        Path csv = csv("," + "__HEADER__," + q(HEADER), ",ex:Line," + q(LINE));

        ConstraintCsvRestore.Result result = ConstraintCsvRestore.restore(csv, tempDir.resolve("out"));

        assertThat(result.ttlFiles()).extracting(p -> p.getFileName().toString()).containsExactly("in.ttl");
    }

    /**
     * Output names come from the CSV. Traversal, UNC paths and mixed separators are reduced to the
     * last segment, which stays inside the output folder (security rules: path checks fail closed).
     */
    @ParameterizedTest
    @ValueSource(strings = {"../../escape.ttl", "..\\..\\escape.ttl", "\\\\server\\share\\escape.ttl",
            "//server/share/escape.ttl", "sub/..\\../escape.ttl", "/etc/escape", "C:\\Windows\\escape.ttl"})
    void namesThatPointElsewhereStayInsideTheOutputFolder(String sourceFile) throws Exception {
        Path csv = csv(q(sourceFile) + ",__HEADER__," + q(HEADER));
        Path out = tempDir.resolve("out");

        ConstraintCsvRestore.Result result = ConstraintCsvRestore.restore(csv, out);

        assertThat(result.ttlFiles()).containsExactly(out.toRealPath().resolve("escape.ttl"));
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("in.csv", "out");
        }
    }

    /** A drive-relative name (an NTFS alternate data stream on Windows) or a device name is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"C:escape.ttl", "EQ:hidden.ttl", "CON.ttl", "nul", "com1.ttl", "bad\u0001name.ttl"})
    void unsafeNamesAreRefusedAndNothingIsWritten(String sourceFile) throws Exception {
        Path csv = csv(q(sourceFile) + ",__HEADER__," + q(HEADER));
        Path out = tempDir.resolve("out");

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, out)).isInstanceOf(IOException.class);
        try (var files = Files.list(out)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    /** A payload that is not Turtle fails the CSV and leaves the existing file untouched. */
    @Test
    void invalidTurtleIsAnErrorAndReplacesNothing() throws Exception {
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Files.writeString(out.resolve("EQ.ttl"), "# previous version\n");
        Path csv = csv("EQ.ttl,__HEADER__," + q(HEADER), "EQ.ttl,ex:Line," + q("ex:Line a sh:NodeShape ;; broken"));

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, out))
                .isInstanceOf(IOException.class).hasMessageContaining("EQ.ttl");
        assertThat(Files.readString(out.resolve("EQ.ttl"))).isEqualTo("# previous version\n");
        try (var files = Files.list(out)) {
            assertThat(files.toList()).hasSize(1);
        }
    }

    /** Security review: Windows-reserved characters made resolve() throw an unchecked exception (exit 3). */
    @ParameterizedTest
    @ValueSource(strings = {"a?.ttl", "a<b.ttl", "a>b.ttl", "a|b.ttl", "a*.ttl", "a\"b.ttl", "COM¹.ttl", "conin$.ttl", "CONOUT$"})
    void windowsReservedNamesAreRefusedAsIoErrors(String sourceFile) throws Exception {
        Path csv = csv(q(sourceFile) + ",__HEADER__," + q(HEADER));

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, tempDir.resolve("out"))).isInstanceOf(IOException.class);
    }

    /** Security review: a refused name later in the CSV must not leave earlier files already replaced. */
    @Test
    void aRefusedNameLaterInTheCsvReplacesNothing() throws Exception {
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Files.writeString(out.resolve("EQ.ttl"), "# previous version\n");
        Path csv = csv("EQ.ttl,__HEADER__," + q(HEADER), "EQ.ttl,ex:Line," + q(LINE), "CON.ttl,__HEADER__," + q(HEADER));

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, out)).isInstanceOf(IOException.class);
        assertThat(Files.readString(out.resolve("EQ.ttl"))).isEqualTo("# previous version\n");
    }

    /** An invalid second file must not leave the first one already replaced either. */
    @Test
    void invalidTurtleInALaterFileReplacesNothing() throws Exception {
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Files.writeString(out.resolve("EQ.ttl"), "# previous version\n");
        Path csv = csv("EQ.ttl,__HEADER__," + q(HEADER), "SSH.ttl,ex:Bad," + q("ex:Bad a ;; broken"));

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, out)).isInstanceOf(IOException.class);
        assertThat(Files.readString(out.resolve("EQ.ttl"))).isEqualTo("# previous version\n");
        try (var files = Files.list(out)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("EQ.ttl");
        }
    }

    /** Names that only look odd resolve to an ordinary file inside the folder. */
    @Test
    void edgeNamesBecomeOrdinaryFilesInsideTheFolder() throws Exception {
        Path csv = csv(q("..") + ",__HEADER__," + q(HEADER), q("x.ttl.") + ",__HEADER__," + q(HEADER),
                q("y.ttl ") + ",__HEADER__," + q(HEADER), q("dir/") + ",__HEADER__," + q(HEADER));
        Path out = tempDir.resolve("out");

        ConstraintCsvRestore.Result result = ConstraintCsvRestore.restore(csv, out);

        assertThat(result.ttlFiles()).extracting(p -> p.getFileName().toString())
                .containsExactly("..ttl", "x.ttl.ttl", "y.ttl", "restored.ttl");
        assertThat(result.ttlFiles()).allSatisfy(p -> assertThat(p.getParent()).isEqualTo(out.toRealPath()));
    }

    /** EQ.ttl and eq.ttl are one file on Windows and macOS, so they are one output everywhere. */
    @Test
    void namesDifferingOnlyInCaseAreOneFile() throws Exception {
        Path csv = csv("EQ.ttl,__HEADER__," + q(HEADER), "eq.ttl,ex:Line," + q(LINE));

        ConstraintCsvRestore.Result result = ConstraintCsvRestore.restore(csv, tempDir.resolve("out"));

        assertThat(result.ttlFiles()).extracting(p -> p.getFileName().toString()).containsExactly("EQ.ttl");
        assertThat(RDFDataMgr.loadModel(result.ttlFiles().getFirst().toString()).size()).isEqualTo(2);
    }

    @Test
    void anOverLongNameFailsAndLeavesNoTemporaryFile() throws Exception {
        Path out = tempDir.resolve("out");
        Path csv = csv(q("x".repeat(300) + ".ttl") + ",__HEADER__," + q(HEADER));

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, out)).isInstanceOf(IOException.class);
        try (var files = Files.list(out)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    /** An existing symlink with the target's name is replaced, never followed (security review). */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void anExistingSymlinkIsReplacedNotFollowed() throws Exception {
        Path victim = Files.writeString(tempDir.resolve("victim.ttl"), "# victim\n");
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Files.createSymbolicLink(out.resolve("EQ.ttl"), victim);
        Path csv = csv("EQ.ttl,__HEADER__," + q(HEADER));

        ConstraintCsvRestore.restore(csv, out);

        assertThat(Files.readString(victim)).isEqualTo("# victim\n");
        assertThat(Files.isSymbolicLink(out.resolve("EQ.ttl"))).isFalse();
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aSymlinkedOutputFolderIsResolvedToItsRealPath() throws Exception {
        Path real = Files.createDirectories(tempDir.resolve("real"));
        Path link = Files.createSymbolicLink(tempDir.resolve("link"), real);

        ConstraintCsvRestore.Result result = ConstraintCsvRestore.restore(csv("EQ.ttl,__HEADER__," + q(HEADER)), link);

        assertThat(result.ttlFiles()).containsExactly(real.toRealPath().resolve("EQ.ttl"));
    }

    /** The refusal message quotes the CSV, so bidi overrides and control characters are neutralised. */
    @Test
    void refusalMessagesNeutraliseUntrustedText() throws Exception {
        Path csv = csv(q("a‮?\u009B.ttl") + ",__HEADER__," + q(HEADER));

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, tempDir.resolve("out")))
                .isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("‮").doesNotContain("\u009B"));
    }

    /** Security review: an unterminated quote over many lines was scanned in quadratic time. */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    void anUnterminatedQuoteOverManyLinesIsReadInLinearTime() throws Exception {
        StringBuilder b = new StringBuilder("SourceFile,Payload\nEQ.ttl,\"");
        b.append("line of an unterminated field\n".repeat(200_000));
        Path csv = Files.writeString(tempDir.resolve("in.csv"), b.toString());

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, tempDir.resolve("out"))).isInstanceOf(IOException.class);
    }

    @Test
    void aCsvWithoutAPayloadColumnIsAnError() throws Exception {
        Path csv = Files.writeString(tempDir.resolve("in.csv"), "SourceFile,Other\nEQ.ttl,x\n");

        assertThatThrownBy(() -> ConstraintCsvRestore.restore(csv, tempDir.resolve("out")))
                .isInstanceOf(IOException.class).hasMessageContaining("Payload");
    }

    @Test
    void anEmptyCsvWritesNothing() throws Exception {
        Path csv = Files.writeString(tempDir.resolve("in.csv"), "SourceFile,Payload\n");

        assertThat(ConstraintCsvRestore.restore(csv, tempDir.resolve("out")).ttlFiles()).isEqualTo(List.of());
    }
}
