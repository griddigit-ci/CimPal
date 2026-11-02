/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** QoCDC 3.2.1 rules → Excel (GUI QoCDC 3.2.1 xml to Excel, CLI {@code qocdc2xlsx}). */
class QocdcRulesTest {

    /** The shape of a QoCDC 3.2.1 rule file: relative IRIs resolved against the CGM-BP base. */
    static final String RULES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="#Rule_B">
                <rdf:type rdf:resource="#UMLRestrictionRule"/>
                <UMLRestrictionRule.name xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">BusbarSection:B</UMLRestrictionRule.name>
                <UMLRestrictionRule.severity xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">Error</UMLRestrictionRule.severity>
                <UMLRestrictionRule.description xml:lang="en" xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">Checks B.</UMLRestrictionRule.description>
                <UMLRestrictionRule.level xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">2</UMLRestrictionRule.level>
              </rdf:Description>
              <rdf:Description rdf:about="#Rule_A">
                <rdf:type rdf:resource="#UMLRestrictionRule"/>
                <UMLRestrictionRule.name xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">ACLineSegment:A</UMLRestrictionRule.name>
                <UMLRestrictionRule.message xmlns="http://entsoe.eu/CIM/Extensions/CGM-BP/2020#">=A failed</UMLRestrictionRule.message>
              </rdf:Description>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    @Test
    void oneRowPerRuleSortedByNameWithNaForMissingValues() throws Exception {
        Path file = Files.writeString(tempDir.resolve("QoCDC.xml"), RULES);

        List<List<Object>> rows = QocdcRules.rows(QocdcRules.load(file));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("ACLineSegment:A", "NA", "NA", "=A failed", "NA");
        assertThat(rows.get(1)).containsExactly("BusbarSection:B", "Error", "Checks B.", "NA", "2");
    }
}
