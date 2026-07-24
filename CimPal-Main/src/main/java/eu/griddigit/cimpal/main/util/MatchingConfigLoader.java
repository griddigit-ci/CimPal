/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.util;

import eu.griddigit.cimpal.core.matching.model.MatchingConfig;
import eu.griddigit.cimpal.core.presets.MatchingConfigPresets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads a {@link MatchingConfig} from an external JSON file, falling back to
 * {@link MatchingConfigPresets#defaults()} for any field that is absent. Kept in
 * the Main module because Jackson lives here; the Core engine stays
 * dependency-free and receives only the built config object.
 *
 * <p>Every key is optional, so a partial JSON file overrides just the values it
 * names. Unknown keys are ignored.</p>
 */
public final class MatchingConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MatchingConfigLoader() {
    }

    /** Returns the defaults when {@code configFile} is null. */
    public static MatchingConfig load(File configFile) throws IOException {
        if (configFile == null) {
            return MatchingConfigPresets.defaults();
        }
        JsonNode root = MAPPER.readTree(Files.readString(configFile.toPath()));
        MatchingConfig.Builder b = MatchingConfig.builder();

        applyDouble(root, "rTolerance", b::rTolerance);
        applyDouble(root, "xTolerance", b::xTolerance);
        applyDouble(root, "lengthTolerance", b::lengthTolerance);
        applyDouble(root, "bchTolerance", b::bchTolerance);
        applyDouble(root, "voltageTolerance", b::voltageTolerance);

        if (root.has("queryFolder") && !root.get("queryFolder").isNull()) {
            String qf = root.get("queryFolder").asString();
            if (qf != null && !qf.isBlank()) b.queryFolder(qf);
        }
        if (root.has("secondNameSource")) {
            b.secondNameSource(parseSecondName(root.get("secondNameSource").asString()));
        }
        if (root.has("classesToMap") && root.get("classesToMap").isArray()) {
            List<String> classes = new ArrayList<>();
            for (JsonNode n : root.get("classesToMap")) {
                classes.add(n.asString());
            }
            if (!classes.isEmpty()) b.classesToMap(classes);
        }
        return b.build();
    }

    private static void applyDouble(JsonNode root, String key, java.util.function.DoubleConsumer setter) {
        if (root.has(key) && root.get(key).isNumber()) {
            setter.accept(root.get(key).asDouble());
        }
    }

    private static MatchingConfig.SecondNameSource parseSecondName(String s) {
        if (s == null) return MatchingConfig.SecondNameSource.NAME;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "aliasname", "alias_name", "alias" -> MatchingConfig.SecondNameSource.ALIAS_NAME;
            case "description", "desc" -> MatchingConfig.SecondNameSource.DESCRIPTION;
            default -> MatchingConfig.SecondNameSource.NAME;
        };
    }
}
