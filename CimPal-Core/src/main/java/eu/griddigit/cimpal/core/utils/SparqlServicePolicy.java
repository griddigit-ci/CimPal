/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.Query;
import org.apache.jena.query.SortCondition;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.exec.http.Service;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.ExprVisitorBase;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementBind;
import org.apache.jena.sparql.syntax.ElementFilter;
import org.apache.jena.sparql.syntax.ElementLateral;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementVisitorBase;
import org.apache.jena.sparql.syntax.ElementWalker;
import org.apache.jena.sparql.util.Context;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps SPARQL {@code SERVICE} from reaching the network (SEC-2, gap G3).
 *
 * <p>Jena ARQ executes {@code SERVICE <url>} by default, which would let a user-supplied query
 * (or a SHACL-SPARQL constraint in user-supplied shapes) send HTTP requests to any host,
 * including loopback and private addresses, past the egress allowlist in
 * {@code ValidationTools}. CimPal has no use for federated queries, so SERVICE is refused:
 * {@link #requireNoService} rejects such queries before they run, and
 * {@link #disableRemoteServiceGlobally} switches remote SERVICE off for every query execution in
 * the JVM, as a backstop for code paths that don't go through {@link SparqlTools}.
 */
public final class SparqlServicePolicy {

    private SparqlServicePolicy() {
    }

    /**
     * Turns off remote SERVICE execution in the global ARQ context. Called once at start-up by
     * the CLI and the GUI; a SERVICE clause then fails instead of sending a request.
     */
    public static void disableRemoteServiceGlobally() {
        disableIn(ARQ.getContext());
    }

    /** Turns off remote SERVICE execution in {@code context} (for one query execution). */
    public static void disableIn(Context context) {
        context.set(Service.httpServiceAllowed, false);
        context.set(Service.serviceAllowed, false);
    }

    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final Set<String> SHACL_SPARQL_PROPERTIES = Set.of("select", "ask", "construct", "update");
    private static final Pattern SERVICE_KEYWORD = Pattern.compile("(?i)\\bSERVICE\\b");

    /**
     * Throws when a SHACL-SPARQL constraint or rule in {@code shapes} ({@code sh:select},
     * {@code sh:ask}, {@code sh:construct}, {@code sh:update}) mentions SERVICE. Used before
     * shapes go to an external engine (pySHACL, Rust SHACL), which the Jena switches can't
     * reach. The check is textual, so a query that merely contains the word fails too: it fails
     * closed rather than parsing prefixes the constraint may declare elsewhere.
     *
     * @throws IllegalArgumentException naming the problem
     */
    public static void requireNoServiceInShapes(Model shapes) {
        for (Statement statement : shapes.listStatements().toList()) {
            Property predicate = statement.getPredicate();
            if (SH.equals(predicate.getNameSpace())
                    && SHACL_SPARQL_PROPERTIES.contains(predicate.getLocalName())
                    && statement.getObject().isLiteral()
                    && SERVICE_KEYWORD.matcher(statement.getLiteral().getLexicalForm()).find()) {
                throw new IllegalArgumentException("A SHACL-SPARQL constraint uses SERVICE (federated query), "
                        + "which is not allowed.");
            }
        }
    }

    /**
     * Throws when {@code query} contains a SERVICE clause anywhere, including sub-queries and
     * EXISTS / NOT EXISTS / MINUS blocks.
     *
     * @throws IllegalArgumentException naming the problem
     */
    public static void requireNoService(Query query) {
        if (queryContainsService(query)) {
            throw new IllegalArgumentException(
                    "SPARQL SERVICE (federated query) is not allowed: CimPal only queries the loaded models.");
        }
    }

    /** The WHERE pattern plus every expression an EXISTS could hide in: SELECT, GROUP BY, HAVING, ORDER BY. */
    private static boolean queryContainsService(Query query) {
        if (containsService(query.getQueryPattern())) {
            return true;
        }
        for (Expr expr : query.getProject().getExprs().values()) {
            if (exprContainsService(expr)) {
                return true;
            }
        }
        if (query.hasGroupBy()) {
            for (Expr expr : query.getGroupBy().getExprs().values()) {
                if (exprContainsService(expr)) {
                    return true;
                }
            }
        }
        if (query.hasHaving()) {
            for (Expr expr : query.getHavingExprs()) {
                if (exprContainsService(expr)) {
                    return true;
                }
            }
        }
        if (query.hasOrderBy()) {
            for (SortCondition condition : query.getOrderBy()) {
                if (exprContainsService(condition.getExpression())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Walks {@code element}, descending into sub-queries and EXISTS / NOT EXISTS expressions. */
    private static boolean containsService(Element element) {
        if (element == null) {
            return false;
        }
        boolean[] found = {false};
        ElementWalker.walk(element, new ElementVisitorBase() {
            @Override
            public void visit(ElementService el) {
                found[0] = true;
            }

            @Override
            public void visit(ElementSubQuery el) {
                found[0] |= queryContainsService(el.getQuery());
            }

            @Override
            public void visit(ElementLateral el) {
                found[0] |= containsService(el.getLateralElement());
            }

            @Override
            public void visit(ElementFilter el) {
                found[0] |= exprContainsService(el.getExpr());
            }

            @Override
            public void visit(ElementBind el) {
                found[0] |= exprContainsService(el.getExpr());
            }
        });
        return found[0];
    }

    private static boolean exprContainsService(Expr expr) {
        boolean[] found = {false};
        Walker.walk(expr, new ExprVisitorBase() {
            @Override
            public void visit(ExprFunctionOp funcOp) {
                found[0] |= containsService(funcOp.getElement());
            }
        });
        return found[0];
    }
}
