/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Makes "the same grid with other IDs" out of a model, as the second model of an ID mapping test,
 * and keeps the ground truth: every object defined in the model gets a new IRI and mRID; names
 * can be scrambled, line sections merged, node containers dropped and line parameters scaled.
 * Deterministic for a given seed.
 */
final class ModelRewriter {

    static final String CIM = SyntheticGrid.CIM;

    private final Model model;
    private final Random random;
    /** Old IRI to new IRI of every object that survives. */
    private final Map<String, String> truth = new HashMap<>();
    /** Old IRIs of objects removed by {@link #mergeSeries}, mapped to the object that absorbed them. */
    private final Map<String, String> absorbed = new HashMap<>();

    private ModelRewriter(Model source, long seed) {
        this.model = ModelFactory.createDefaultModel().add(source);
        this.model.setNsPrefixes(source.getNsPrefixMap());
        this.random = new Random(seed);
    }

    static ModelRewriter of(Model source, long seed) {
        return new ModelRewriter(source, seed);
    }

    /** Gives every subject of the model (except {@code keep}) a new IRI and mRID; call last. */
    ModelRewriter renameIds(Set<String> keep) {
        Map<Resource, Resource> renames = new HashMap<>();
        for (Resource s : model.listSubjects().toList()) {
            if (s.isURIResource() && !keep.contains(s.getURI())) {
                String uuid = new UUID(random.nextLong(), random.nextLong()).toString();
                renames.put(s, model.createResource(CIM + "_" + uuid));
            }
        }
        Model out = ModelFactory.createDefaultModel();
        out.setNsPrefixes(model.getNsPrefixMap());
        Property mrid = model.createProperty(CIM + "IdentifiedObject.mRID");
        for (Statement st : model.listStatements().toList()) {
            Resource s = renames.getOrDefault(st.getSubject(), st.getSubject());
            RDFNode o = st.getObject();
            if (o.isURIResource() && renames.containsKey(o.asResource())) {
                o = renames.get(o.asResource());
            }
            if (st.getPredicate().equals(mrid) && renames.containsKey(st.getSubject())) {
                o = out.createLiteral(GridData.localId(s.getURI()));
            }
            out.add(s, st.getPredicate(), o);
        }
        renames.forEach((from, to) -> truth.put(from.getURI(), to.getURI()));
        absorbed.replaceAll((gone, into) -> truth.getOrDefault(into, into));
        model.removeAll().add(out);
        return this;
    }

    /** Replaces the names of a share of the objects of the given classes by random text. */
    ModelRewriter scrambleNames(Set<String> classes, double share) {
        Property name = model.createProperty(CIM + "IdentifiedObject.name");
        for (String cls : classes) {
            for (Resource r : model.listSubjectsWithProperty(RDF.type, model.createResource(CIM + cls)).toList()) {
                if (random.nextDouble() < share) {
                    model.removeAll(r, name, null);
                    r.addProperty(name, "X" + Long.toHexString(random.nextLong()));
                }
            }
        }
        return this;
    }

    /** Removes the container of the nodes the predicate selects. */
    ModelRewriter dropNodeContainers(Predicate<Resource> which) {
        Property container = model.createProperty(CIM + "ConnectivityNode.ConnectivityNodeContainer");
        for (Resource cn : model.listSubjectsWithProperty(RDF.type, model.createResource(CIM + "ConnectivityNode")).toList()) {
            if (which.test(cn)) {
                model.removeAll(cn, container, null);
            }
        }
        return this;
    }

    /**
     * Removes a share of the switches of the given class, joining their two nodes into one (the
     * first), as a model drawn with fewer switches would have it.
     */
    ModelRewriter removeSwitches(String cls, double share) {
        Property eqOf = model.createProperty(CIM + "Terminal.ConductingEquipment");
        Property cnOf = model.createProperty(CIM + "Terminal.ConnectivityNode");
        for (Resource sw : model.listSubjectsWithProperty(RDF.type, model.createResource(CIM + cls)).toList()) {
            if (random.nextDouble() >= share) {
                continue;
            }
            List<Resource> terminals = model.listSubjectsWithProperty(eqOf, sw).toList();
            if (terminals.size() != 2) {
                continue;
            }
            Resource keep = terminals.get(0).getPropertyResourceValue(cnOf);
            Resource drop = terminals.get(1).getPropertyResourceValue(cnOf);
            if (keep == null || drop == null || keep.equals(drop)) {
                continue;
            }
            for (Resource t : model.listSubjectsWithProperty(cnOf, drop).toList()) {
                model.removeAll(t, cnOf, null);
                t.addProperty(cnOf, keep);
            }
            Set<Resource> gone = new HashSet<>(terminals);
            gone.add(sw);
            gone.add(drop);
            for (Resource r : gone) {
                model.removeAll(r, null, null);
                model.removeAll(null, null, r);
            }
        }
        return this;
    }

    /**
     * Moves a voltage level into a substation of its own, as a model that keeps each voltage level
     * of a station as a separate substation (FINGLAS / FINGLAS1) would have it. The transformer
     * between the voltage levels then joins the two substations.
     */
    ModelRewriter splitStation(String voltageLevelIri, String model2SubstationName) {
        Resource vl = model.getResource(voltageLevelIri);
        Resource sub = model.createResource(CIM + "_split_" + Integer.toHexString(voltageLevelIri.hashCode()));
        sub.addProperty(RDF.type, model.createResource(CIM + "Substation"));
        sub.addProperty(model.createProperty(CIM + "IdentifiedObject.name"), model2SubstationName);
        Property vlSub = model.createProperty(CIM + "VoltageLevel.Substation");
        model.removeAll(vl, vlSub, null);
        vl.addProperty(vlSub, sub);
        return this;
    }

    /**
     * Cuts a line section in two at a junction: a substation of its own (named {@code name}) with a
     * voltage level, one node and, if asked, a busbar section, as PowerFactory exports a terminal
     * between line sections ({@code Term_...}). The section keeps the first half; a new section
     * ({@code <section>_b}) takes the second. r, x, bch and length are halved.
     */
    ModelRewriter addJunction(String sectionIri, String name, boolean withBusbar) {
        Resource section = model.getResource(sectionIri);
        Property eqOf = model.createProperty(CIM + "Terminal.ConductingEquipment");
        Property cnOf = model.createProperty(CIM + "Terminal.ConnectivityNode");
        Property typeName = model.createProperty(CIM + "IdentifiedObject.name");
        List<Resource> terminals = model.listSubjectsWithProperty(eqOf, section).toList();
        terminals.sort((a, b) -> a.getURI().compareTo(b.getURI()));
        Resource far = terminals.get(1);
        Resource farNode = far.getPropertyResourceValue(cnOf);
        String id = sectionIri.substring(sectionIri.lastIndexOf('_'));
        Resource sub = typed(sectionIri + "_jsub", "Substation", name);
        Resource vl = typed(sectionIri + "_jvl", "VoltageLevel", name + " VL");
        vl.addProperty(model.createProperty(CIM + "VoltageLevel.Substation"), sub);
        Resource bv = section.getPropertyResourceValue(model.createProperty(CIM + "ConductingEquipment.BaseVoltage"));
        if (bv != null) {
            vl.addProperty(model.createProperty(CIM + "VoltageLevel.BaseVoltage"), bv);
        }
        Resource node = typed(sectionIri + "_jcn", "ConnectivityNode", name);
        node.addProperty(model.createProperty(CIM + "ConnectivityNode.ConnectivityNodeContainer"), vl);
        if (withBusbar) {
            Resource bb = typed(sectionIri + "_jbb", "BusbarSection", name);
            bb.addProperty(model.createProperty(CIM + "Equipment.EquipmentContainer"), vl);
            terminal(sectionIri + "_jbb_t", bb, node, 1);
        }
        Resource second = typed(sectionIri + "_b", "ACLineSegment", section.getProperty(typeName).getString() + id + "b");
        for (Statement st : section.listProperties().toList()) {
            String p = st.getPredicate().getLocalName();
            if (st.getPredicate().equals(RDF.type) || p.endsWith("name") || p.endsWith("mRID")) {
                continue;
            }
            if (st.getObject().isLiteral() && p.matches(".*\\.(r|x|bch|gch|length)$")) {
                double half = Double.parseDouble(st.getString()) / 2;
                model.remove(st);
                section.addProperty(st.getPredicate(), String.valueOf(half));
                second.addProperty(st.getPredicate(), String.valueOf(half));
            } else {
                second.addProperty(st.getPredicate(), st.getObject());
            }
        }
        model.removeAll(far, cnOf, null);
        far.addProperty(cnOf, node);
        terminal(sectionIri + "_b_t1", second, node, 1);
        terminal(sectionIri + "_b_t2", second, farNode, 2);
        return this;
    }

    private Resource typed(String iri, String cls, String name) {
        Resource r = model.createResource(iri);
        r.addProperty(RDF.type, model.createResource(CIM + cls));
        r.addProperty(model.createProperty(CIM + "IdentifiedObject.name"), name);
        r.addProperty(model.createProperty(CIM + "IdentifiedObject.mRID"), GridData.localId(iri));
        return r;
    }

    private void terminal(String iri, Resource equipment, Resource node, int seq) {
        Resource t = typed(iri, "Terminal", "T" + seq);
        t.addProperty(model.createProperty(CIM + "Terminal.ConductingEquipment"), equipment);
        t.addProperty(model.createProperty(CIM + "Terminal.ConnectivityNode"), node);
        t.addProperty(model.createProperty(CIM + "ACDCTerminal.sequenceNumber"), String.valueOf(seq));
    }

    /** Adds {@code count} objects of a class the source does not have, e.g. measurements. */
    ModelRewriter addObjects(String cls, int count) {
        for (int i = 0; i < count; i++) {
            typed(CIM + "_extra_" + cls + i, cls, cls + " " + i);
        }
        return this;
    }

    /**
     * Replaces the names of objects of one class by station-code style abbreviations: upper case,
     * no vowels after the first letter, at most eight letters (Agannygal becomes AGNNYGL).
     */
    ModelRewriter abbreviateNames(String cls) {
        Property name = model.createProperty(CIM + "IdentifiedObject.name");
        for (Resource r : model.listSubjectsWithProperty(RDF.type, model.createResource(CIM + cls)).toList()) {
            Statement st = r.getProperty(name);
            if (st == null) {
                continue;
            }
            String letters = st.getString().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]", "");
            if (letters.isEmpty()) {
                continue;
            }
            String code = letters.charAt(0) + letters.substring(1).replaceAll("[AEIOU]", "");
            model.remove(st);
            r.addProperty(name, code.substring(0, Math.min(8, code.length())));
        }
        return this;
    }

    /** Renames one object. */
    ModelRewriter rename(String iri, String name) {
        Resource r = model.getResource(iri);
        Property p = model.createProperty(CIM + "IdentifiedObject.name");
        model.removeAll(r, p, null);
        r.addProperty(p, name);
        return this;
    }

    /** Multiplies r, x and bch of every ACLineSegment by {@code factor}. */
    ModelRewriter scaleLineParameters(double factor) {
        for (String p : List.of("ACLineSegment.r", "ACLineSegment.x", "ACLineSegment.bch")) {
            Property prop = model.createProperty(CIM + p);
            for (Statement st : model.listStatements(null, prop, (RDFNode) null).toList()) {
                double v = Double.parseDouble(st.getString());
                model.remove(st);
                model.add(st.getSubject(), prop, String.valueOf(v * factor));
            }
        }
        return this;
    }

    /**
     * Merges two line sections that share a node into the first: the first takes over the second's
     * far node and the sum of the parameters; the second, its terminals, limits and the shared node go.
     */
    ModelRewriter mergeSeries(String firstIri, String secondIri) {
        Resource first = model.getResource(firstIri);
        Resource second = model.getResource(secondIri);
        Property eqOf = model.createProperty(CIM + "Terminal.ConductingEquipment");
        Property cnOf = model.createProperty(CIM + "Terminal.ConnectivityNode");
        List<Resource> firstTerminals = model.listSubjectsWithProperty(eqOf, first).toList();
        List<Resource> secondTerminals = model.listSubjectsWithProperty(eqOf, second).toList();
        Resource shared = null;
        for (Resource t : firstTerminals) {
            Resource cn = t.getPropertyResourceValue(cnOf);
            for (Resource u : secondTerminals) {
                if (cn.equals(u.getPropertyResourceValue(cnOf))) {
                    shared = cn;
                }
            }
        }
        if (shared == null) {
            throw new IllegalArgumentException("sections share no node");
        }
        Resource far = null;
        for (Resource u : secondTerminals) {
            if (!shared.equals(u.getPropertyResourceValue(cnOf))) {
                far = u.getPropertyResourceValue(cnOf);
            }
        }
        for (Resource t : firstTerminals) {
            if (shared.equals(t.getPropertyResourceValue(cnOf))) {
                model.removeAll(t, cnOf, null);
                t.addProperty(cnOf, far);
            }
        }
        for (String p : List.of("ACLineSegment.r", "ACLineSegment.x", "ACLineSegment.bch", "Conductor.length")) {
            Property prop = model.createProperty(CIM + p);
            double sum = first.getProperty(prop).getDouble() + second.getProperty(prop).getDouble();
            model.removeAll(first, prop, null);
            first.addProperty(prop, String.valueOf(sum));
        }
        Set<Resource> gone = new HashSet<>(secondTerminals);
        gone.add(second);
        gone.add(shared);
        Property limitEquipment = model.createProperty(CIM + "OperationalLimitSet.Equipment");
        Property limitSet = model.createProperty(CIM + "OperationalLimit.OperationalLimitSet");
        for (Resource ols : model.listSubjectsWithProperty(limitEquipment, second).toList()) {
            gone.add(ols);
            gone.addAll(model.listSubjectsWithProperty(limitSet, ols).toList());
        }
        for (Resource r : gone) {
            model.removeAll(r, null, null);
            model.removeAll(null, null, r);
        }
        absorbed.put(secondIri, firstIri);
        return this;
    }

    Model model() {
        return model;
    }

    /** Old local ID to new local ID of every renamed object. */
    Map<String, String> truthById() {
        Map<String, String> byId = new HashMap<>();
        truth.forEach((from, to) -> byId.put(GridData.localId(from), GridData.localId(to)));
        return byId;
    }

    /** Old local IDs of merged-away objects, to the new local ID of the object that absorbed them. */
    Map<String, String> absorbedById() {
        Map<String, String> byId = new HashMap<>();
        absorbed.forEach((from, to) -> byId.put(GridData.localId(from), GridData.localId(to)));
        return byId;
    }

    static void write(Model model, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            RDFDataMgr.write(out, model, Lang.RDFXML);
        }
    }

    static Set<String> subjects(Model model) {
        Set<String> out = new HashSet<>();
        model.listSubjects().forEachRemaining(r -> {
            if (r.isURIResource()) {
                out.add(r.getURI());
            }
        });
        return out;
    }
}
