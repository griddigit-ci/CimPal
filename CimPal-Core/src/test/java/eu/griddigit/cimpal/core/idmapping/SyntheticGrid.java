/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

/**
 * A small node-breaker CGMES 3.0 grid built in code for the ID mapping tests:
 * <pre>
 *   A (400 kV double busbar + coupler, 400/110 kV transformer, 110 kV load)
 *   |  \\ two parallel A-B circuits (same parameters, different names)
 *   |   B (single busbar, generator)
 *   |   |
 *   C   D (single busbar, load)
 *    \_/   C-D is two sections through a node in the Line container
 *   D - boundary node (a tie line half ending at the boundary, with an EquivalentInjection)
 * </pre>
 * The BaseVoltages and the boundary node are in {@link #common()}, as in a real boundary set.
 */
final class SyntheticGrid {

    static final String CIM = "http://iec.ch/TC57/CIM100#";
    static final String IRI = CIM;

    private final Model eq = ModelFactory.createDefaultModel();
    private final Model common = ModelFactory.createDefaultModel();
    private Resource bv400;
    private Resource bv110;

    private SyntheticGrid() {
        eq.setNsPrefix("cim", CIM);
        common.setNsPrefix("cim", CIM);
    }

    static SyntheticGrid build() {
        SyntheticGrid g = new SyntheticGrid();
        g.grid();
        return g;
    }

    Model eq() {
        return eq;
    }

    Model common() {
        return common;
    }

    private void grid() {
        bv400 = obj(common, "BaseVoltage", "bv400", "400 kV");
        lit(common, bv400, "BaseVoltage.nominalVoltage", "400");
        bv110 = obj(common, "BaseVoltage", "bv110", "110 kV");
        lit(common, bv110, "BaseVoltage.nominalVoltage", "110");
        Resource boundaryLine = obj(common, "Line", "bndLine", "Boundary line");
        Resource boundaryNode = obj(common, "ConnectivityNode", "bndNode", "BND-1");
        ref(common, boundaryNode, "ConnectivityNode.ConnectivityNodeContainer", boundaryLine);

        // Substation A: 400 kV double busbar, coupler, two bays to B, one to C, transformer to 110 kV
        Resource a = obj(eq, "Substation", "subA", "Alpha");
        Resource a400 = vl("vlA400", "Alpha 400", a, bv400);
        Resource a110 = vl("vlA110", "Alpha 110", a, bv110);
        Resource aBb1 = cn("cnA_bb1", a400);
        Resource aBb2 = cn("cnA_bb2", a400);
        busbar("bbA1", "Alpha BB1", a400, aBb1);
        busbar("bbA2", "Alpha BB2", a400, aBb2);
        Resource aCpl = cn("cnA_cpl", a400);
        sw("Breaker", "brA_cpl", "Alpha coupler", a400, aBb1, aCpl);
        sw("Disconnector", "dsA_cpl", "Alpha coupler DS", a400, aCpl, aBb2);
        Resource aB1 = bay("A", "AB1", a400, aBb1, aBb2);
        Resource aB2 = bay("A", "AB2", a400, aBb2, aBb1);
        Resource aC = bay("A", "AC", a400, aBb1, null);
        Resource aT = bay("A", "AT", a400, aBb2, null);
        Resource a110bb = cn("cnA110_bb", a110);
        busbar("bbA110", "Alpha 110 BB", a110, a110bb);
        transformer("trA", "Alpha T1", a, aT, 400, a110bb, 110);
        Resource aLoadCn = cn("cnA110_load", a110);
        sw("Breaker", "brA110_load", "Alpha load CB", a110, a110bb, aLoadCn);
        load("ldA", "Alpha load", a110, aLoadCn);

        // Substation B: single busbar, bays to A (x2) and D, generator
        Resource b = obj(eq, "Substation", "subB", "Bravo");
        Resource b400 = vl("vlB400", "Bravo 400", b, bv400);
        Resource bBb = cn("cnB_bb", b400);
        busbar("bbB", "Bravo BB", b400, bBb);
        Resource bA1 = bay("B", "BA1", b400, bBb, null);
        Resource bA2 = bay("B", "BA2", b400, bBb, null);
        Resource bD = bay("B", "BD", b400, bBb, null);
        Resource bG = bay("B", "BG", b400, bBb, null);
        machine("gB", "Bravo G1", b400, bG);

        // Substation C: single busbar, bays to A and D
        Resource c = obj(eq, "Substation", "subC", "Charlie");
        Resource c400 = vl("vlC400", "Charlie 400", c, bv400);
        Resource cBb = cn("cnC_bb", c400);
        busbar("bbC", "Charlie BB", c400, cBb);
        Resource cA = bay("C", "CA", c400, cBb, null);
        Resource cD = bay("C", "CD", c400, cBb, null);

        // Substation D: single busbar, bays to B, C, the boundary, and a load
        Resource d = obj(eq, "Substation", "subD", "Delta");
        Resource d400 = vl("vlD400", "Delta 400", d, bv400);
        Resource dBb = cn("cnD_bb", d400);
        busbar("bbD", "Delta BB", d400, dBb);
        Resource dB = bay("D", "DB", d400, dBb, null);
        Resource dC = bay("D", "DC", d400, dBb, null);
        Resource dX = bay("D", "DX", d400, dBb, null);
        Resource dL = bay("D", "DL", d400, dBb, null);
        load("ldD", "Delta load", d400, dL);

        // Lines
        Resource lAB1 = obj(eq, "Line", "lineAB1", "Alpha-Bravo 1");
        acls("aclsAB1", "Alpha-Bravo 1", lAB1, 2.0, 20.0, 1e-4, 80, aB1, bA1);
        Resource lAB2 = obj(eq, "Line", "lineAB2", "Alpha-Bravo 2");
        acls("aclsAB2", "Alpha-Bravo 2", lAB2, 2.0, 20.0, 1e-4, 80, aB2, bA2);
        Resource lAC = obj(eq, "Line", "lineAC", "Alpha-Charlie");
        acls("aclsAC", "Alpha-Charlie", lAC, 1.5, 15.0, 8e-5, 60, aC, cA);
        Resource lBD = obj(eq, "Line", "lineBD", "Bravo-Delta");
        acls("aclsBD", "Bravo-Delta", lBD, 3.0, 31.0, 1.5e-4, 120, bD, dB);
        Resource lCD = obj(eq, "Line", "lineCD", "Charlie-Delta");
        Resource mid = cn("cnLineCD_mid", lCD);
        acls("aclsCD1", "Charlie-Delta s1", lCD, 1.0, 10.0, 5e-5, 40, cD, mid);
        acls("aclsCD2", "Charlie-Delta s2", lCD, 1.25, 12.5, 6e-5, 50, mid, dC);
        Resource lDX = obj(eq, "Line", "lineDX", "Delta-Boundary");
        acls("aclsDX", "Delta-Boundary", lDX, 0.5, 5.0, 2e-5, 20, dX, boundaryNode);
        Resource inj = obj(eq, "EquivalentInjection", "eiX", "Boundary injection");
        ref(eq, inj, "ConductingEquipment.BaseVoltage", bv400);
        terminal("eiX", 1, inj, boundaryNode);
    }

    /** A line bay: line-end node - breaker - node - disconnector - busbar (and a second disconnector to busbar 2). */
    private Resource bay(String sub, String name, Resource vl, Resource busbar1, Resource busbar2) {
        Resource lineEnd = cn("cn" + name + "_end", vl);
        Resource middle = cn("cn" + name + "_mid", vl);
        sw("Breaker", "br" + name, sub + " " + name + " CB", vl, lineEnd, middle);
        sw("Disconnector", "ds" + name + "_1", sub + " " + name + " DS1", vl, middle, busbar1);
        if (busbar2 != null) {
            sw("Disconnector", "ds" + name + "_2", sub + " " + name + " DS2", vl, middle, busbar2);
        }
        return lineEnd;
    }

    private Resource vl(String id, String name, Resource sub, Resource bv) {
        Resource vl = obj(eq, "VoltageLevel", id, name);
        ref(eq, vl, "VoltageLevel.Substation", sub);
        ref(eq, vl, "VoltageLevel.BaseVoltage", bv);
        return vl;
    }

    private Resource cn(String id, Resource container) {
        Resource cn = obj(eq, "ConnectivityNode", id, id);
        ref(eq, cn, "ConnectivityNode.ConnectivityNodeContainer", container);
        return cn;
    }

    private void busbar(String id, String name, Resource vl, Resource cn) {
        Resource bb = obj(eq, "BusbarSection", id, name);
        ref(eq, bb, "Equipment.EquipmentContainer", vl);
        terminal(id, 1, bb, cn);
    }

    private void sw(String type, String id, String name, Resource vl, Resource cn1, Resource cn2) {
        Resource s = obj(eq, type, id, name);
        ref(eq, s, "Equipment.EquipmentContainer", vl);
        terminal(id, 1, s, cn1);
        terminal(id, 2, s, cn2);
    }

    private void load(String id, String name, Resource vl, Resource cn) {
        Resource l = obj(eq, "ConformLoad", id, name);
        ref(eq, l, "Equipment.EquipmentContainer", vl);
        terminal(id, 1, l, cn);
    }

    private void machine(String id, String name, Resource vl, Resource cn) {
        Resource m = obj(eq, "SynchronousMachine", id, name);
        ref(eq, m, "Equipment.EquipmentContainer", vl);
        terminal(id, 1, m, cn);
        Resource unit = obj(eq, "GeneratingUnit", id + "_unit", name + " unit");
        ref(eq, m, "RotatingMachine.GeneratingUnit", unit);
    }

    private void acls(String id, String name, Resource line, double r, double x, double b, double length,
                      Resource cn1, Resource cn2) {
        Resource s = obj(eq, "ACLineSegment", id, name);
        ref(eq, s, "Equipment.EquipmentContainer", line);
        ref(eq, s, "ConductingEquipment.BaseVoltage", bv400);
        lit(eq, s, "ACLineSegment.r", String.valueOf(r));
        lit(eq, s, "ACLineSegment.x", String.valueOf(x));
        lit(eq, s, "ACLineSegment.bch", String.valueOf(b));
        lit(eq, s, "Conductor.length", String.valueOf(length));
        terminal(id, 1, s, cn1);
        terminal(id, 2, s, cn2);
        Resource limits = obj(eq, "OperationalLimitSet", id + "_ols", name + " limits");
        ref(eq, limits, "OperationalLimitSet.Equipment", s);
        Resource patl = obj(eq, "CurrentLimit", id + "_patl", "PATL");
        ref(eq, patl, "OperationalLimit.OperationalLimitSet", limits);
    }

    private void transformer(String id, String name, Resource sub, Resource cnHv, double uHv, Resource cnLv, double uLv) {
        Resource t = obj(eq, "PowerTransformer", id, name);
        ref(eq, t, "Equipment.EquipmentContainer", sub);
        Resource t1 = terminal(id, 1, t, cnHv);
        Resource t2 = terminal(id, 2, t, cnLv);
        end(id + "_e1", t, 1, uHv, t1);
        end(id + "_e2", t, 2, uLv, t2);
    }

    private void end(String id, Resource t, int number, double ratedU, Resource terminal) {
        Resource e = obj(eq, "PowerTransformerEnd", id, id);
        ref(eq, e, "PowerTransformerEnd.PowerTransformer", t);
        ref(eq, e, "TransformerEnd.Terminal", terminal);
        lit(eq, e, "TransformerEnd.endNumber", String.valueOf(number));
        lit(eq, e, "PowerTransformerEnd.ratedU", String.valueOf(ratedU));
    }

    private Resource terminal(String eqId, int seq, Resource equipment, Resource cn) {
        Resource t = obj(eq, "Terminal", eqId + "_t" + seq, eqId + " T" + seq);
        ref(eq, t, "Terminal.ConductingEquipment", equipment);
        ref(eq, t, "Terminal.ConnectivityNode", cn);
        lit(eq, t, "ACDCTerminal.sequenceNumber", String.valueOf(seq));
        return t;
    }

    private static Resource obj(Model m, String type, String id, String name) {
        Resource r = m.createResource(IRI + "_" + id);
        r.addProperty(RDF.type, m.createResource(CIM + type));
        lit(m, r, "IdentifiedObject.mRID", id);
        lit(m, r, "IdentifiedObject.name", name);
        return r;
    }

    private static void ref(Model m, Resource s, String property, Resource o) {
        s.addProperty(m.createProperty(CIM + property), o);
    }

    private static void lit(Model m, Resource s, String property, String value) {
        s.addProperty(m.createProperty(CIM + property), value);
    }
}
