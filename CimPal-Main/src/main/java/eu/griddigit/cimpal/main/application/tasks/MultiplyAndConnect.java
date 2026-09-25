/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.GuiHelper;
import eu.griddigit.cimpal.main.application.datagenerator.ModelManipulationFactory;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import javafx.scene.control.TextArea;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.util.*;

public class MultiplyAndConnect implements ITask {

    // Classes whose rdf:ID survives a copy, so every copy sits in the same containers and under
    // the same header rather than duplicating the grid structure around itself.
    private static final List<String> SHARED_CLASSES =
            List.of("FullModel", "ControlArea", "LoadArea", "SubGeographicalRegion", "GeographicalRegion");

    // Impedance of the line that joins one copy to the next. Small enough to be electrically
    // close to a busbar coupling without being a numerical zero.
    private static final String TIE_LINE_R = "0.011";
    private static final String TIE_LINE_X = "0.011";
    private static final String TIE_LINE_BCH = "0.0001875";
    private static final String TIE_LINE_GCH = "0";
    private static final String TIE_LINE_LENGTH = "0.01";

    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private Integer timesToMultiply;
    private String connectivityNodeId;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public MultiplyAndConnect() {
        this.name = "Multiply and connect";
        this.pathToFXML = "/fxml/wizardPages/taskElements/multiplyNTimesAndConnect.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.saveResult = true;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        System.out.println("Executing task: " + this.name);
        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent, "Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();

        taskUpdater.updateState(parent, "Executing", "10%", wizardContext);

        String xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();
        Map<String, BaseInstanceModel> instanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();

        // The originals are read-only from here on: every copy, and the union the copies are
        // merged into, is built from its own clone, so the model as loaded stays intact.
        Map<String, Model> originalModels = new HashMap<>();
        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            originalModels.put(entry.getKey(), copyOf(entry.getValue().getBaseInstanceModel()));
        }

        String equipmentFile = fileOfProfile(instanceModel, "EQ");
        if (equipmentFile == null) {
            throw new IOException("No EQ file among the selected instance models - there is nothing to connect the copies to.");
        }
        String steadyStateFile = fileOfProfile(instanceModel, "SSH");
        if (steadyStateFile == null) {
            report("No SSH file among the selected instance models - the terminals of the connecting lines are "
                    + "created without their in-service state.");
        }
        String topologyFile = fileOfProfile(instanceModel, "TP");
        if (topologyFile == null) {
            report("No TP file among the selected instance models - the terminals of the connecting lines are "
                    + "not bound to a topological node.");
        }

        // The field takes the node UUID with or without the leading underscore of the rdf:ID form.
        String baseConnectivityNode = "_" + connectivityNodeId.trim().replaceFirst("^#?_?", "");
        Resource connectivityNode = ResourceFactory.createResource(xmlBase + "#" + baseConnectivityNode);

        // The node is looked for across all the selected files, not just EQ. A bus-branch model
        // carries no ConnectivityNode in EQ at all - the node and its link to a TopologicalNode
        // are in TP - so requiring it in EQ turned an ordinary IGM into an error.
        String connectivityNodeFile = fileHoldingConnectivityNode(instanceModel, originalModels, connectivityNode);
        if (connectivityNodeFile == null) {
            throw new IOException("Connectivity node " + baseConnectivityNode + " is not in any of the selected files: "
                    + String.join(", ", new TreeSet<>(instanceModel.keySet())) + ".");
        }
        report("Connectivity node " + baseConnectivityNode + " found in " + connectivityNodeFile + ".");

        // The connecting line has to run at the voltage of the node it attaches to. This used to
        // be a hard-coded 400 kV BaseVoltage resource, which only resolved in the one model the
        // task was first written against and left a dangling reference everywhere else.
        Resource baseVoltage = baseVoltageOf(originalModels.values(), connectivityNode, xmlBase);
        if (baseVoltage == null) {
            throw new IOException("Could not work out the base voltage of connectivity node " + baseConnectivityNode
                    + ": it needs either a TopologicalNode that declares TopologicalNode.BaseVoltage in TP, or a "
                    + "VoltageLevel container that declares VoltageLevel.BaseVoltage in EQ.");
        }

        // What the copies are merged into, seeded with the original model.
        Map<String, Model> unionByFile = new HashMap<>();
        for (Map.Entry<String, Model> original : originalModels.entrySet()) {
            unionByFile.put(original.getKey(), copyOf(original.getValue()));
        }

        // The node the next copy attaches to. It starts as the original one and then becomes each
        // copy own node, so the copies come out as a chain instead of all hanging off one node.
        String previousConnectivityNode = baseConnectivityNode;
        int copiesToMake = timesToMultiply - 1;

        for (int m = 0; m < copiesToMake; m++) {
            // Copy first, regenerate second, so the new ids land on the copy and the original is
            // still there to make the next copy from.
            Map<String, Model> copies = new HashMap<>();
            for (Map.Entry<String, Model> original : originalModels.entrySet()) {
                copies.put(original.getKey(), copyOf(original.getValue()));
            }

            Map<String, String> idMap = new HashMap<>();
            Map<String, Model> copy = ModelManipulationFactory.regenerateRDFIDmodule(copies, SHARED_CLASSES, idMap);

            String newConnectivityNode = idMap.get(baseConnectivityNode);
            if (newConnectivityNode == null) {
                throw new IOException("Copy " + (m + 1) + " did not get a new id for connectivity node "
                        + baseConnectivityNode + ", so it cannot be connected.");
            }

            for (Map.Entry<String, Model> entry : copy.entrySet()) {
                Model union = unionByFile.get(entry.getKey());
                if (union != null) {
                    union.add(entry.getValue());
                }
            }

            connect(unionByFile.get(equipmentFile),
                    steadyStateFile == null ? null : unionByFile.get(steadyStateFile),
                    topologyFile == null ? null : unionByFile.get(topologyFile),
                    xmlBase, previousConnectivityNode, newConnectivityNode, baseVoltage, m);

            previousConnectivityNode = newConnectivityNode;

            int progress = 10 + (int) ((double) (m + 1) / copiesToMake * 80);
            taskUpdater.updateState(parent, "Connected copy " + (m + 1) + " of " + copiesToMake, progress + "%", wizardContext);
        }

        for (Map.Entry<String, Model> union : unionByFile.entrySet()) {
            instanceModel.get(union.getKey()).setBaseInstanceModel(union.getValue());
        }

        taskUpdater.updateState(parent, "Processing Completed, saving result", "90%", wizardContext);
        // saving the result - once, after every copy is in, rather than once per copy
        wizardContext.saveModel(parent, saveResult);

        taskUpdater.updateState(parent, "Completed", "100%", wizardContext);
    }

    /**
     * Adds the line that joins the copy just made to whatever it follows: an ACLineSegment in its
     * own Line container, with a terminal on each of the two connectivity nodes.
     */
    private static void connect(Model equipmentUnion, Model steadyStateUnion, Model topologyUnion, String xmlBase,
                                String fromConnectivityNode, String toConnectivityNode,
                                Resource baseVoltage, int copyIndex) {

        Resource line = newResource(xmlBase);
        Resource acLineSegment = newResource(xmlBase);
        Resource terminal1 = newResource(xmlBase);
        Resource terminal2 = newResource(xmlBase);

        List<Statement> equipmentStatements = new ArrayList<>();

        equipmentStatements.add(stmt(acLineSegment, RDF.type, res(xmlBase, "ACLineSegment")));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "ACLineSegment.bch"), TIE_LINE_BCH));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "ACLineSegment.gch"), TIE_LINE_GCH));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "ACLineSegment.r"), TIE_LINE_R));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "ACLineSegment.x"), TIE_LINE_X));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "Conductor.length"), TIE_LINE_LENGTH));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "IdentifiedObject.name"), "ACLineSegment " + copyIndex));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "Equipment.EquipmentContainer"), line));
        equipmentStatements.add(stmt(acLineSegment, prop(xmlBase, "ConductingEquipment.BaseVoltage"), baseVoltage));

        equipmentStatements.add(stmt(line, RDF.type, res(xmlBase, "Line")));
        equipmentStatements.add(stmt(line, prop(xmlBase, "IdentifiedObject.name"), "LineEB " + copyIndex));
        // Line.Region is optional here: a model without a SubGeographicalRegion would otherwise
        // have stopped the whole task on a missing-element error.
        ResIterator regions = equipmentUnion.listSubjectsWithProperty(RDF.type, res(xmlBase, "SubGeographicalRegion"));
        if (regions.hasNext()) {
            equipmentStatements.add(stmt(line, prop(xmlBase, "Line.Region"), regions.next()));
        }

        addTerminal(equipmentStatements, xmlBase, terminal1, acLineSegment, fromConnectivityNode, "1", copyIndex);
        addTerminal(equipmentStatements, xmlBase, terminal2, acLineSegment, toConnectivityNode, "2", copyIndex);

        equipmentUnion.add(equipmentStatements);

        if (steadyStateUnion != null) {
            List<Statement> steadyStateStatements = new ArrayList<>();
            for (Resource terminal : List.of(terminal1, terminal2)) {
                steadyStateStatements.add(stmt(terminal, RDF.type, res(xmlBase, "Terminal")));
                steadyStateStatements.add(stmt(terminal, prop(xmlBase, "ACDCTerminal.connected"), "true"));
            }
            steadyStateUnion.add(steadyStateStatements);
        }

        if (topologyUnion != null) {
            // Terminal.ConnectivityNode alone leaves the line unconnected for a bus-branch model,
            // where the connectivity node is only a step on the way to the topological node that
            // the solver actually works with, so each terminal is bound to that node in TP too.
            List<Statement> topologyStatements = new ArrayList<>();
            bindToTopologicalNode(topologyStatements, topologyUnion, xmlBase, terminal1, fromConnectivityNode);
            bindToTopologicalNode(topologyStatements, topologyUnion, xmlBase, terminal2, toConnectivityNode);
            topologyUnion.add(topologyStatements);
        }
    }

    /**
     * Adds Terminal.TopologicalNode for one terminal, reading the node off the connectivity node it
     * was attached to in EQ.
     * <p>
     * The copy has already been merged into the union by the time this runs, so the node of a copy
     * resolves to that copy's own, newly generated one. Terminal.TopologicalNode is the direction
     * CGMES writes - both 2.4.15 and 3.0 mark it AssociationUsed, and the inverse not.
     */
    private static void bindToTopologicalNode(List<Statement> into, Model topologyUnion, String xmlBase,
                                              Resource terminal, String connectivityNode) {
        Resource topologicalNode = objectOf(List.of(topologyUnion),
                ResourceFactory.createResource(xmlBase + "#" + connectivityNode),
                prop(xmlBase, "ConnectivityNode.TopologicalNode"));

        if (topologicalNode == null) {
            report("Connectivity node " + connectivityNode + " has no TopologicalNode in TP, so the terminal "
                    + "attached to it is left without Terminal.TopologicalNode.");
            return;
        }

        // Terminal is described in EQ, so in TP it is carried as an rdf:about reference; the type
        // statement is what makes the writer emit the element at all.
        into.add(stmt(terminal, RDF.type, res(xmlBase, "Terminal")));
        into.add(stmt(terminal, prop(xmlBase, "Terminal.TopologicalNode"), topologicalNode));
    }

    private static void addTerminal(List<Statement> into, String xmlBase, Resource terminal, Resource conductingEquipment,
                                    String connectivityNode, String sequenceNumber, int copyIndex) {
        into.add(stmt(terminal, RDF.type, res(xmlBase, "Terminal")));
        into.add(stmt(terminal, prop(xmlBase, "IdentifiedObject.name"), "TerminalEB-" + sequenceNumber + " " + copyIndex));
        into.add(stmt(terminal, prop(xmlBase, "ACDCTerminal.sequenceNumber"), sequenceNumber));
        into.add(stmt(terminal, prop(xmlBase, "Terminal.ConductingEquipment"), conductingEquipment));
        // The node rdf:ID already carries its leading underscore, so it is not added again here.
        // The two terminals used to spell the same node differently - one with the underscore and
        // one without - which left the line attached to a node that did not exist.
        into.add(stmt(terminal, prop(xmlBase, "Terminal.ConnectivityNode"),
                ResourceFactory.createResource(xmlBase + "#" + connectivityNode)));
    }

    /**
     * The file that describes the given connectivity node, preferring TP.
     * <p>
     * ConnectivityNode is declared in both profiles: EQ describes it for a node-breaker model,
     * while TP is where it appears for a bus-branch one, carrying only its link to a
     * TopologicalNode. TP is therefore the profile to trust when both mention the node.
     */
    private static String fileHoldingConnectivityNode(Map<String, BaseInstanceModel> instanceModel,
                                                      Map<String, Model> models, Resource connectivityNode) {
        String elsewhere = null;
        for (Map.Entry<String, Model> entry : models.entrySet()) {
            if (!entry.getValue().contains(connectivityNode, RDF.type)) {
                continue;
            }
            if ("TP".equals(instanceModel.get(entry.getKey()).getProfile())) {
                return entry.getKey();
            }
            if (elsewhere == null) {
                elsewhere = entry.getKey();
            }
        }
        return elsewhere;
    }

    /**
     * The BaseVoltage the given connectivity node runs at, looked for across every selected file
     * because the two routes to it live in different profiles.
     * <p>
     * A bus-branch model gets there through TP: the node points at a TopologicalNode, which
     * declares the base voltage. A node-breaker model gets there through EQ: the node sits in a
     * VoltageLevel, possibly by way of a Bay. Returns null when neither route is in the data,
     * which the caller reports rather than writing a reference to a base voltage that is not there.
     */
    private static Resource baseVoltageOf(Collection<Model> models, Resource connectivityNode, String xmlBase) {
        Resource topologicalNode = objectOf(models, connectivityNode, prop(xmlBase, "ConnectivityNode.TopologicalNode"));
        if (topologicalNode != null) {
            Resource baseVoltage = objectOf(models, topologicalNode, prop(xmlBase, "TopologicalNode.BaseVoltage"));
            if (baseVoltage != null) {
                return baseVoltage;
            }
        }

        Resource container = objectOf(models, connectivityNode, prop(xmlBase, "ConnectivityNode.ConnectivityNodeContainer"));
        if (container == null) {
            return null;
        }

        Resource baseVoltage = objectOf(models, container, prop(xmlBase, "VoltageLevel.BaseVoltage"));
        if (baseVoltage != null) {
            return baseVoltage;
        }

        // A node inside a Bay hangs off the VoltageLevel one step further up.
        Resource voltageLevel = objectOf(models, container, prop(xmlBase, "Bay.VoltageLevel"));
        return voltageLevel == null ? null : objectOf(models, voltageLevel, prop(xmlBase, "VoltageLevel.BaseVoltage"));
    }

    private static Resource objectOf(Collection<Model> models, Resource subject, Property predicate) {
        for (Model model : models) {
            if (model.contains(subject, predicate)) {
                RDFNode object = model.listStatements(subject, predicate, (RDFNode) null).next().getObject();
                if (object.isResource()) {
                    return object.asResource();
                }
            }
        }
        return null;
    }

    private static String fileOfProfile(Map<String, BaseInstanceModel> instanceModel, String profile) {
        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            if (profile.equals(entry.getValue().getProfile())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static Model copyOf(Model model) {
        Model copy = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        copy.setNsPrefixes(model.getNsPrefixMap());
        copy.add(model);
        return copy;
    }

    private static Resource newResource(String xmlBase) {
        return ResourceFactory.createResource(xmlBase + "#_" + UUID.randomUUID());
    }

    private static Resource res(String xmlBase, String localName) {
        return ResourceFactory.createResource(xmlBase + "#" + localName);
    }

    private static Property prop(String xmlBase, String localName) {
        return ResourceFactory.createProperty(xmlBase + "#" + localName);
    }

    private static Statement stmt(Resource subject, Property predicate, RDFNode object) {
        return ResourceFactory.createStatement(subject, predicate, object);
    }

    private static Statement stmt(Resource subject, Property predicate, String literal) {
        return ResourceFactory.createStatement(subject, predicate, ResourceFactory.createPlainLiteral(literal));
    }

    private static void report(String message) {
        TextArea output = WizardContext.getExecutionTextArea();
        if (output != null) {
            GuiHelper.appendTextToOutputWindow(output, message, true);
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPathToFXMLComponent() {
        return this.pathToFXML;
    }

    @Override
    public String getStatus() {
        return this.status;
    }

    @Override
    public String getInfo() {
        return this.info;
    }

    public void setTimesToMultiply(Integer timesToMultiply) {
        this.timesToMultiply = timesToMultiply;
    }

    public void setConnectivityNodeId(String connectivityNodeId) {
        this.connectivityNodeId = connectivityNodeId;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    @Override
    public String validateInputs() {
        var message = "";
        // The count is the total, and the base model is one of them, so below two there is
        // nothing to copy and nothing to connect.
        if (timesToMultiply == null || timesToMultiply < 2) {
            message += "Number of models has to be 2 or more (the base model counts as one) for task: " + name + "\n";
        }

        if (connectivityNodeId == null || connectivityNodeId.isBlank()) {
            message += "No Connectivity Node Id input for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}
