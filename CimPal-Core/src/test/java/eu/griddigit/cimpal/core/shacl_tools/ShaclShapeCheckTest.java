/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.utils.ModelFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SHACL-SHACL check of shape files (GUI Validate SHACL shapes, CLI {@code shacl-check}). */
class ShaclShapeCheckTest {

    private static final Model SHACL_SHACL = ModelFactory.LoadSHACLSHACL();

    private static Model shapes(String body) {
        Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        RDFParser.fromString("""
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix ex: <http://example.org/s#> .
                """ + body, Lang.TURTLE).parse(m);
        return m;
    }

    @Test
    void wellFormedShapesConform() {
        ShaclShapeCheck.Result result = ShaclShapeCheck.check("ok.ttl", shapes("""
                ex:S a sh:NodeShape ; sh:targetClass ex:C ;
                    sh:property [ sh:path ex:p ; sh:minCount 1 ; sh:datatype xsd:string ] .
                """), SHACL_SHACL);

        assertThat(result.conforms()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void aMalformedShapeIsReportedWithItsNodeAndMessage() {
        ShaclShapeCheck.Result result = ShaclShapeCheck.check("bad.ttl", shapes("""
                ex:S a sh:NodeShape ; sh:targetClass ex:C ;
                    sh:property ex:P .
                ex:P a sh:PropertyShape ; sh:path ex:p ; sh:minCount "one" .
                """), SHACL_SHACL);

        assertThat(result.conforms()).isFalse();
        assertThat(result.name()).isEqualTo("bad.ttl");
        assertThat(result.findings()).isNotEmpty();
        assertThat(result.findings()).anySatisfy(f -> {
            assertThat(f.focusNode()).isEqualTo("ex:P");
            assertThat(f.message()).isNotBlank();
        });
    }
}
