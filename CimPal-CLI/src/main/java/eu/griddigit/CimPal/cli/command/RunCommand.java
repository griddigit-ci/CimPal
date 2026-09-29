/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code run} subcommand — execute a declarative JSON pipeline of CimPal commands.
 *
 * <p>Each step in the pipeline is a JSON object with a {@code command} key naming the
 * subcommand to run, plus any option keys that subcommand's config file accepts.  The step
 * JSON is written to a temporary file and passed as {@code --config} to the subcommand, so
 * all existing config-loading and path-resolution behaviour carries over automatically.
 *
 * <p>Steps run sequentially.  By default the pipeline stops on any error (exit &ge; 2) but
 * continues through steps that find violations (exit 1).  Both behaviours are configurable at
 * the pipeline level and per-step.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — all steps passed with no violations
 *   <li>1 — all steps ran; at least one found violations
 *   <li>2 — bad input (pipeline file not found, malformed JSON, missing command key)
 *   <li>3 — a step returned an internal error or the pipeline was aborted by a stop condition
 * </ul>
 */
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Execute a declarative JSON pipeline of CimPal commands.",
        sortOptions = false
)
public class RunCommand implements Callable<Integer> {

    private static final String SCHEMA = "cimpal-pipeline-result/1";

    /** Keys whose presence in a step JSON object are consumed by RunCommand, not forwarded. */
    private static final List<String> META_KEYS =
            List.of("command", "id", "name", "stopOnError", "stopOnViolations");

    @Parameters(index = "0", description = "Pipeline JSON file.", paramLabel = "<pipeline.json>")
    private File pipelineFile;

    @Option(names = "--dry-run",
            description = "Print the resolved pipeline steps and exit without executing anything.")
    private boolean dryRun;

    @Option(names = "--format",
            description = "Output format: text (default) or json.")
    private String format;

    // -------------------------------------------------------------------------

    @Override
    public Integer call() {
        if (format == null) format = "text";

        // ---- load pipeline --------------------------------------------------
        if (!pipelineFile.exists() || !pipelineFile.isFile()) {
            System.err.println("[ERROR] Pipeline file not found: " + pipelineFile.getAbsolutePath());
            return ExitCode.INVALID_INPUT;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode pipeline;
        try {
            pipeline = mapper.readTree(pipelineFile);
        } catch (Exception ex) {
            System.err.println("[ERROR] Could not parse pipeline file: " + ex.getMessage());
            return ExitCode.INVALID_INPUT;
        }

        Path pipelineDir = pipelineFile.toPath().toAbsolutePath().getParent();

        String pipelineName = pipeline.path("name").asText("(unnamed)");
        String pipelineDescription = pipeline.path("description").asText(null);
        boolean globalStopOnError      = pipeline.path("stopOnError").asBoolean(true);
        boolean globalStopOnViolations = pipeline.path("stopOnViolations").asBoolean(false);

        JsonNode stepsNode = pipeline.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            System.err.println("[ERROR] Pipeline has no steps or 'steps' is not an array.");
            return ExitCode.INVALID_INPUT;
        }

        int totalSteps = stepsNode.size();

        // ---- dry-run header -------------------------------------------------
        if (dryRun) {
            System.out.println("=== Pipeline (dry run): " + pipelineName + " ===");
            if (pipelineDescription != null) System.out.println("    " + pipelineDescription);
            System.out.println("    Steps: " + totalSteps
                    + "  stopOnError=" + globalStopOnError
                    + "  stopOnViolations=" + globalStopOnViolations);
            for (int i = 0; i < totalSteps; i++) {
                JsonNode step = stepsNode.get(i);
                String cmd  = step.path("command").asText("(missing)");
                String id   = step.path("id").asText("step-" + (i + 1));
                String nm   = step.path("name").asText(cmd);
                System.out.printf("  [%d/%d] id=%-20s command=%-20s name=%s%n",
                        i + 1, totalSteps, id, cmd, nm);
                String cfg = step.path("config").asText(null);
                if (cfg != null) System.out.println("         config: " + cfg);
            }
            return ExitCode.OK;
        }

        // ---- execution ------------------------------------------------------
        boolean jsonOutput = "json".equalsIgnoreCase(format);
        if (!jsonOutput) {
            System.out.println("=== Pipeline: " + pipelineName + " ===");
            if (pipelineDescription != null) System.out.println("    " + pipelineDescription);
            System.out.println();
        }

        List<StepResult> results = new ArrayList<>();
        int overallViolations = 0;
        boolean stopped = false;

        for (int i = 0; i < totalSteps; i++) {
            JsonNode step = stepsNode.get(i);
            String command = step.path("command").asText(null);
            String id      = step.path("id").asText("step-" + (i + 1));
            String nm      = step.path("name").asText(command != null ? command : id);

            if (command == null || command.isBlank()) {
                System.err.println("[ERROR] Step " + (i + 1) + " (" + id + ") is missing 'command'.");
                results.add(new StepResult(id, nm, null, ExitCode.INVALID_INPUT, "missing command key"));
                stopped = true;
                break;
            }

            boolean stepStopOnError      = step.path("stopOnError").asBoolean(globalStopOnError);
            boolean stepStopOnViolations = step.path("stopOnViolations").asBoolean(globalStopOnViolations);

            if (!jsonOutput) {
                System.out.printf("[%d/%d] %s (%s)...%n", i + 1, totalSteps, nm, command);
            }

            // Resolve config path relative to pipeline file directory
            String configPathRaw = step.path("config").asText(null);
            String resolvedConfigPath = null;
            if (configPathRaw != null && !configPathRaw.isBlank()) {
                Path p = Path.of(configPathRaw);
                resolvedConfigPath = (p.isAbsolute() ? p : pipelineDir.resolve(p))
                        .normalize().toString();
            }

            // Write the step JSON to a temp config file.
            // Commands ignore meta-keys (command/id/name/stop*) they don't recognise,
            // so we can pass the entire step node unchanged.
            // If a 'config' key is present we update it to the resolved absolute path.
            ObjectNode stepForConfig = (ObjectNode) step.deepCopy();
            if (resolvedConfigPath != null) {
                stepForConfig.put("config", resolvedConfigPath);
            }

            int exitCode;
            Path tempConfig = null;
            try {
                tempConfig = Files.createTempFile("cimpal-pipeline-", ".json");
                mapper.writeValue(tempConfig.toFile(), stepForConfig);

                String[] args = {command, "--config", tempConfig.toString()};

                // Suppress stdout during step execution in json output mode so our
                // pipeline JSON is not interleaved with step progress output.
                PrintStream origOut = System.out;
                if (jsonOutput) System.setOut(System.err);
                try {
                    exitCode = new CommandLine(new CimPalCli()).execute(args);
                } finally {
                    if (jsonOutput) System.setOut(origOut);
                }

            } catch (Exception ex) {
                System.err.println("[ERROR] Step " + id + " failed to launch: " + ex.getMessage());
                exitCode = ExitCode.INTERNAL_ERROR;
            } finally {
                if (tempConfig != null) {
                    try { Files.deleteIfExists(tempConfig); } catch (Exception ignored) {}
                }
            }

            String status = switch (exitCode) {
                case 0 -> "OK";
                case 1 -> "VIOLATIONS";
                case 2 -> "INVALID_INPUT";
                default -> "ERROR";
            };

            results.add(new StepResult(id, nm, command, exitCode, status));

            if (!jsonOutput) {
                System.out.println("  -> " + status + " (exit " + exitCode + ")");
            }

            if (exitCode == 1) overallViolations++;

            if (exitCode >= 2 && stepStopOnError) {
                if (!jsonOutput) {
                    System.err.println("[PIPELINE STOPPED] Step " + id + " failed — aborting remaining steps.");
                }
                stopped = true;
                break;
            }
            if (exitCode == 1 && stepStopOnViolations) {
                if (!jsonOutput) {
                    System.err.println("[PIPELINE STOPPED] Step " + id + " found violations and stopOnViolations=true.");
                }
                stopped = true;
                break;
            }
        }

        // ---- summary --------------------------------------------------------
        int passed     = (int) results.stream().filter(r -> r.exitCode == 0).count();
        int violations = (int) results.stream().filter(r -> r.exitCode == 1).count();
        int failed     = (int) results.stream().filter(r -> r.exitCode >= 2).count();
        int skipped    = totalSteps - results.size();

        if (!jsonOutput) {
            System.out.println();
            System.out.println("=== Pipeline summary: " + pipelineName + " ===");
            System.out.printf("  Passed: %d  Violations: %d  Failed: %d  Skipped: %d%n",
                    passed, violations, failed, skipped);
            if (stopped) System.out.println("  Pipeline was stopped before all steps ran.");
        } else {
            System.out.println(buildJson(pipelineName, results, totalSteps, passed,
                    violations, failed, skipped, stopped));
        }

        // ---- overall exit code ----------------------------------------------
        if (failed > 0) return ExitCode.INTERNAL_ERROR;
        if (violations > 0) return ExitCode.VIOLATIONS;
        return ExitCode.OK;
    }

    // -------------------------------------------------------------------------
    // JSON output
    // -------------------------------------------------------------------------

    private String buildJson(String name, List<StepResult> results, int total,
                             int passed, int violations, int failed, int skipped, boolean stopped) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n");
        sb.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"pipeline\": ").append(jsonStr(name)).append(",\n");
        sb.append("  \"totalSteps\": ").append(total).append(",\n");
        sb.append("  \"steps\": [\n");
        for (int i = 0; i < results.size(); i++) {
            StepResult r = results.get(i);
            sb.append("    {");
            sb.append("\"id\":").append(jsonStr(r.id)).append(",");
            sb.append("\"name\":").append(jsonStr(r.name)).append(",");
            sb.append("\"command\":").append(jsonStr(r.command)).append(",");
            sb.append("\"exitCode\":").append(r.exitCode).append(",");
            sb.append("\"status\":").append(jsonStr(r.status));
            sb.append("}");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"passed\": ").append(passed).append(",\n");
        sb.append("    \"violations\": ").append(violations).append(",\n");
        sb.append("    \"failed\": ").append(failed).append(",\n");
        sb.append("    \"skipped\": ").append(skipped).append(",\n");
        sb.append("    \"stopped\": ").append(stopped).append("\n");
        sb.append("  }\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsonStr(String v) {
        if (v == null) return "null";
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // -------------------------------------------------------------------------
    // Internal step result
    // -------------------------------------------------------------------------

    private record StepResult(String id, String name, String command, int exitCode, String status) {}
}
