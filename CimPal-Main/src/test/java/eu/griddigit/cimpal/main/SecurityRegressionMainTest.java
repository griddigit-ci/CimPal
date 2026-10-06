/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main;

import eu.griddigit.cimpal.core.testsupport.SourceScan;
import eu.griddigit.cimpal.main.ai.AiKnowledgeSearch;
import eu.griddigit.cimpal.main.ai.OllamaClient;
import eu.griddigit.cimpal.main.application.services.TaskDependenciesEnforcer;
import eu.griddigit.cimpal.main.core.ExportSHACLInformation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the CimPal-Main remediations in SECURITY-SELF-ATTESTATION.md §4 (TEST-2),
 * named {@code F<n>_...} / {@code H1_...} after their finding. Core findings are in
 * {@code SecurityRegressionTest}; the finding to test map is in docs/plans/TEST-2.md.
 */
class SecurityRegressionMainTest {

    @TempDir
    Path tempDir;

    private static Object call(Class<?> owner, String name, Class<?>[] types, Object... args) throws Throwable {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object field(Class<?> owner, String name) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    // ---- F3: OS command construction --------------------------------------------------------

    @Test
    void F3_noSingleStringRuntimeExecAnywhere() {
        assertThat(SourceScan.linesMatching("Runtime\\.getRuntime\\(\\)\\.exec\\(|\\.exec\\(\\s*\"",
                "CimPal-Core", "CimPal-Main", "CimPal-CLI", "CimPal-CustomWriter")).isEmpty();
    }

    @Test
    void F3_outputFolderIsOpenedThroughDesktopNotAShellCommand() {
        String source = SourceScan.read("CimPal-Main/src/main/java/eu/griddigit/cimpal/main/application/"
                + "controllers/taskWizardControllers/TaskStatusController.java");

        assertThat(source).contains("Desktop.getDesktop().open(");
        // Code only: the fix's own comment explains the old "explorer" command.
        assertThat(SourceScan.linesMatching("(?i)\"explorer", "CimPal-Main")).isEmpty();
    }

    // ---- F4: formula injection in CSV --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx", "=HYPERLINK(\"https://evil.example\",\"ok\")"})
    void F4_shaclInformationCsvNeutralisesFormulaTriggers(String payload) throws Throwable {
        String escaped = (String) call(ExportSHACLInformation.class, "csvEscape", new Class<?>[] {String.class}, payload);
        String content = escaped.startsWith("\"") ? escaped.substring(1) : escaped;

        assertThat(content).startsWith("'");
    }

    @Test
    void F4_benignShaclInformationValuesAreUnchanged() throws Throwable {
        assertThat(call(ExportSHACLInformation.class, "csvEscape", new Class<?>[] {String.class}, "plain value"))
                .isEqualTo("plain value");
        assertThat(call(ExportSHACLInformation.class, "csvEscape", new Class<?>[] {String.class}, "a,b"))
                .isEqualTo("\"a,b\"");
    }

    // ---- F8: archive-extraction guard (the three Main copies) --------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "eu.griddigit.cimpal.main.util.ModelFactory",
            "eu.griddigit.cimpal.main.core.InstanceDataFactory",
            "eu.griddigit.cimpal.main.application.datagenerator.InstanceDataFactory"})
    void F8_destinationGuardIsAStrictNormalisedContainmentCheck(String className) throws Throwable {
        Class<?> owner = Class.forName(className);
        Class<?>[] types = {String.class, String.class};
        String base = tempDir.resolve("out").toString();

        assertThat(call(owner, "isValidDestPath", types, base, tempDir.resolve("out/a.xml").toString())).isEqualTo(true);
        assertThat(call(owner, "isValidDestPath", types, base, tempDir.resolve("out/../escape.xml").toString())).isEqualTo(false);
        assertThat(call(owner, "isValidDestPath", types, base, tempDir.resolve("out-sibling/a.xml").toString())).isEqualTo(false);
        assertThat(call(owner, "isValidDestPath", types, base, base)).isEqualTo(false);
    }

    // ---- F11: error handling -----------------------------------------------------------------

    @Test
    void F11_taskDependenciesLoadAndAnUnknownTaskIsUnconstrained() throws Exception {
        TaskDependenciesEnforcer enforcer = new TaskDependenciesEnforcer();

        // Before the fix a missing resource left the constraints null and this threw an NPE.
        String verdict = enforcer.checkIfAddingTaskIsAllowed("No such task " + System.nanoTime());

        assertThat(verdict).isNotNull();
    }

    // ---- F13: AI knowledge-source retrieval --------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8/x", "https://8.8.8.8:8443/x", "https://user@8.8.8.8/x",
            "https://127.0.0.1/x", "https://10.1.2.3/x", "https://169.254.169.254/latest",
            "https://[fd00::1]/x", "https://[fe80::1]/x", "https://224.0.0.1/x", "https://0.0.0.0/x"})
    void F13_knowledgeSourcesMustBePublicCredentialFreeHttpsOnPort443(String url) {
        assertThatThrownBy(() -> call(AiKnowledgeSearch.class, "validatePublicHttpsUri", new Class<?>[] {String.class}, url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void F13_retrievalNeverFollowsRedirectsAndIsSizeBounded() throws Exception {
        HttpClient client = (HttpClient) field(AiKnowledgeSearch.class, "REMOTE_HTTP_CLIENT");

        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(client.connectTimeout()).isPresent();
        assertThat((int) field(AiKnowledgeSearch.class, "MAX_REMOTE_BYTES")).isLessThanOrEqualTo(2_000_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://localhost:11434", "http://user:pw@localhost:11434", "http://evil.example:11434",
            "http://10.0.0.5:11434", "file:///etc/passwd"})
    void F13_ollamaAcceptsLocalHttpEndpointsOnly(String endpoint) {
        assertThatThrownBy(() -> call(OllamaClient.class, "apiUri", new Class<?>[] {String.class, String.class},
                endpoint, "/api/tags"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void F13_localOllamaEndpointIsAccepted() throws Throwable {
        URI uri = (URI) call(OllamaClient.class, "apiUri", new Class<?>[] {String.class, String.class},
                "http://localhost:11434/", "/api/tags");

        assertThat(uri.toString()).isEqualTo("http://localhost:11434/api/tags");
    }

    // ---- H1: script assembled by concatenation -----------------------------------------------

    @Test
    void H1_helpThemeIsSetThroughTheDomNotExecuteScript() {
        String source = SourceScan.read("CimPal-Main/src/main/java/eu/griddigit/cimpal/main/gui/HelpWindow.java");

        assertThat(source).contains("setAttribute(\"data-theme\"").doesNotContain("executeScript");
    }

    @Test
    void H1_noScriptIsBuiltFromDataInHelpOrAiViews() {
        List<String> concatenated = SourceScan.linesMatching("executeScript\\([^)]*\"\\s*\\+", "CimPal-Main");

        // Known exception, recorded in TEST-2.md: RDFVisualisationController passes a numeric
        // zoom factor it computes itself (not data), so it can't carry script.
        assertThat(concatenated).allSatisfy(line -> assertThat(line).contains("RDFVisualisationController").contains("gridZoom"));
    }
}
