/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.RdfGraphView;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.vocabulary.RDF;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Controller for the <em>RDF Operations &#9656; Visualisation</em> tab.
 * <p>
 * Loads RDF in any syntax Jena has a parser for and presents it as a browsable
 * subject &rarr; predicate &rarr; object tree. Each loaded file (each entry, for ZIP archives)
 * becomes a named graph that can be included in or excluded from the tree independently, so one
 * profile file of a CGMES dataset can be inspected without unloading the rest.
 * <p>
 * Read-only throughout: nothing here writes to the loaded models or to disk.
 */
public class RDFVisualisationController implements Initializable {

    /**
     * Subjects collected per graph before the tree is truncated. A CGMES EQ file holds tens of
     * thousands of subjects; past a few thousand tree rows the view stops being browsable, and
     * the filters are the intended way to narrow it. The status line reports the truncation.
     */
    private static final int MAX_SUBJECTS_PER_GRAPH = 5_000;

    /** Bound on the nodes one "Expand selected" press may open, so a big subtree cannot freeze the view. */
    private static final int MAX_EXPANDED_NODES = 1_000;

    /** Entry-count bound for a single archive - archives arrive from third parties. */
    private static final int MAX_ZIP_ENTRIES = 10_000;

    /**
     * Nodes the diagram will draw. Past a few hundred a node-link view is an unreadable hairball
     * well before it is slow, so the cap is about legibility: narrow the filters or select a
     * subject in the tree to see a neighbourhood instead.
     */
    private static final int MAX_GRAPH_NODES = 400;

    /**
     * Nested children above which a hierarchy node groups them into class folders. A substation
     * with forty children is a wall of rows; the same forty behind five class folders is
     * browsable, and below the threshold a folder level would only cost a click.
     */
    private static final int HIERARCHY_CLASS_GROUP_THRESHOLD = 8;

    /** Folder collecting the subjects that declare no {@code rdf:type}. Sorted last. */
    private static final String NO_TYPE_LABEL = "(no rdf:type)";

    /** Name of the graph produced by Merge. Merging again replaces it. */
    private static final String MERGED_GRAPH_NAME = "Merged Graph";

    private MainController mainController;

    @FXML
    private TextField tfSubjectFilter;
    @FXML
    private TextField tfPredicateFilter;
    @FXML
    private TextField tfObjectFilter;
    @FXML
    private TextField tfFullTextSearch;
    @FXML
    private Button btnApplyFilters;
    @FXML
    private Button btnRemoveGraph;
    @FXML
    private Button btnClearAll;
    @FXML
    private ListView<GraphEntry> lvGraphs;
    @FXML
    private TreeView<NodeValue> tvGraph;
    @FXML
    private Label lblStatus;
    @FXML
    private Label helpLoad;
    @FXML
    private Label helpFilters;
    @FXML
    private Label helpGraphs;
    @FXML
    private Label helpTree;
    @FXML
    private Label helpPanes;
    @FXML
    private Label helpGraphView;
    @FXML
    private Button btnMergeGraphs;
    @FXML
    private Button btnToggleGraphsPane;
    @FXML
    private Button btnToggleTreePane;
    @FXML
    private ComboBox<Grouping> cbGrouping;
    @FXML
    private SplitPane visualisationSplitPane;
    @FXML
    private VBox graphsPane;
    @FXML
    private VBox treePane;
    @FXML
    private VBox graphViewPane;
    @FXML
    private RdfGraphView graphView;
    @FXML
    private CheckBox cbIncludeLiterals;
    @FXML
    private Label lblGraphViewInfo;

    /** Loaded graphs in load order; the ListView is a view onto this list. */
    private final ObservableList<GraphEntry> graphEntries = FXCollections.observableArrayList();

    /**
     * Set while a bulk tick change is in progress, so Select all / Select none rebuild the tree
     * once at the end instead of once per row.
     */
    private boolean suppressRebuild;

    /** Whether each of the two left-hand panes is currently part of the split. */
    private boolean graphsPaneVisible = true;
    private boolean treePaneVisible = true;

    /** Appended to the status line: what the last load could not read. Empty when all was well. */
    private String lastLoadNote = "";

    /**
     * The last filter result. The diagram is drawn from this rather than from the models, so what
     * it shows is exactly what the tree shows - the filters are applied once, in one place.
     */
    private List<GraphRows> lastRows = List.of();

    /**
     * Parent/child views of {@link #lastRows}, one per graph, for the two nesting grouping modes.
     * Cleared whenever the tree is rebuilt, so a new filter result or a new grouping mode never
     * reuses a stale hierarchy.
     */
    private final Map<String, Hierarchy> hierarchies = new HashMap<>();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeHelpTooltips();

        lvGraphs.setItems(graphEntries);
        lvGraphs.setCellFactory(view -> new GraphEntryCell());

        tvGraph.setRoot(new TreeItem<>(NodeValue.info("")));
        tvGraph.setShowRoot(false);

        // Both lists are multi-selection: the diagram is drawn for the union of what is
        // highlighted, so several graphs or several resources can be compared at once.
        lvGraphs.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tvGraph.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        cbGrouping.getItems().setAll(Grouping.values());
        cbGrouping.setValue(Grouping.FLAT);
        // Grouping reshapes rows that are already scanned and filtered, so it rebuilds the tree
        // without touching the models.
        cbGrouping.valueProperty().addListener((obs, oldValue, newValue) -> buildTreeItems());

        graphEntries.addListener((ListChangeListener<GraphEntry>) change ->
                updateControlsEnabled());

        // Highlighting in either the list or the tree narrows the diagram to that scope.
        lvGraphs.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<GraphEntry>) change -> redrawGraphView());
        tvGraph.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<TreeItem<NodeValue>>) change -> redrawGraphView());
        graphView.setOnNodeClicked(id -> setStatus("Selected in graph view: " + id));

        updateControlsEnabled();
        setStatus("No data loaded. Use Load RDF files... to begin.");

        // Also labels the two pane buttons; the divider positions it sets are deferred, because
        // the SplitPane has no width until it is laid out and a position set before that is
        // discarded.
        applyPaneVisibility();
    }

    private void initializeHelpTooltips() {
        GUIhelper.installHelpTooltip(helpLoad,
                "Load one or more RDF files, or ZIP archives of them. Every syntax Apache Jena can parse is "
                        + "accepted: RDF/XML (.rdf, .xml, .owl), Turtle (.ttl), N-Triples (.nt), N-Quads (.nq), "
                        + "TriG (.trig), JSON-LD (.jsonld), RDF/JSON (.rj) and TriX (.trix).\n\n"
                        + "Load RDF files... replaces what is loaded; Add files... keeps it and adds more. "
                        + "The syntax is taken from the file extension.");
        GUIhelper.installHelpTooltip(helpFilters,
                "The subject, predicate and object fields each keep only the triples whose corresponding term "
                        + "contains the text, matched case-insensitively against both the shortened form shown in "
                        + "the tree and the full URI or literal value.\n\n"
                        + "Full text search keeps a triple when any one of its three terms matches. Combining "
                        + "fields narrows the result: all non-empty fields must match.\n\n"
                        + "Filters are applied when you press Enter in a field or press Apply - not on every "
                        + "keystroke, because a large dataset has to be rescanned each time.");
        GUIhelper.installHelpTooltip(helpGraphs,
                "One entry per loaded file, or per entry inside a loaded ZIP archive, with its triple count.\n\n"
                        + "The tick box and the row highlight do two different things. Ticking includes a graph "
                        + "in the tree; unticking leaves it out without unloading it. Tick all and Untick all "
                        + "act on every row. When more than one graph is ticked the tree gets a named-graph "
                        + "level above the subjects; with a single graph the subjects are shown directly.\n\n"
                        + "Highlighting a row - clicking its name, with Ctrl or Shift for several - narrows the "
                        + "graph view to those graphs, and tells Remove selected which graphs to unload. "
                        + "Ticking a row does not highlight it, and highlighting one does not tick it.");
        GUIhelper.installHelpTooltip(helpPanes,
                "Named graphs and Graph tree can each be folded away to give the graph view the whole "
                        + "width. The buttons say what pressing them will do and turn into Show Named graphs "
                        + "and Show Graph tree once the pane is away, so bringing one back is never a hunt; "
                        + "the chevron in a pane's own header folds that pane in place.\n\n"
                        + "The panes are removed from the split rather than hidden, so the diagram actually "
                        + "gains the space.");
        GUIhelper.installHelpTooltip(helpGraphView,
                "The relationships between the resources currently shown, drawn as a node-link diagram: "
                        + "one node per resource, one arrow per triple whose object is another resource.\n\n"
                        + "It follows the filters and the highlighted rows. Highlight resources in the tree - "
                        + "Ctrl or Shift for several - to see just those and their immediate neighbours; "
                        + "highlight a class folder to see that class's instances; highlight named graphs, in "
                        + "either the list or the tree, to see only those graphs; highlight nothing to see "
                        + "everything the filters admitted.\n\n"
                        + "Include literals adds attribute values as extra nodes - useful for a handful of "
                        + "resources, overwhelming for many.\n\n"
                        + "Scroll to zoom, drag the background to pan, drag a node to rearrange it, and use "
                        + "Fit to bring everything back into view.");
        GUIhelper.installHelpTooltip(helpTree,
                "The filtered triples, each resource expanding to its predicates and objects. A predicate "
                        + "with one value is shown on a single line; a repeated predicate becomes a node with one "
                        + "child per value.\n\n"
                        + "Group by reshapes the rows already scanned, so it never rescans the models:\n"
                        + "  Subject (flat) - every resource in one alphabetical list.\n"
                        + "  Class (rdf:type) - a folder per class, holding its instances. A resource with two "
                        + "types appears under both.\n"
                        + "  Containment (incoming) - nested by what points at a resource, so a CGMES "
                        + "dataset reads Region, Substation, VoltageLevel, Bay, Equipment, Terminal.\n"
                        + "  References (outgoing) - nested the other way, by what a resource points to.\n\n"
                        + "In the two nesting modes a node shows its nested resources first and its own "
                        + "properties after; past eight nested children they are grouped into class folders.\n\n"
                        + "URIs are shortened using the namespace prefixes declared in the source file, or written "
                        + "relative to the document base where no prefix applies. Copy value puts the full URI or "
                        + "literal of every highlighted row on the clipboard, one per line.\n\n"
                        + "Highlighting rows also narrows the graph view to them.");
    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    private void setProgressBar(double progress) {
        if (mainController != null) {
            mainController.setProgressBarValue(progress);
        }
    }

    private void resetProgressBar() {
        if (mainController != null) {
            mainController.resetProgressBar();
        }
    }

    // ==================== loading ====================

    @FXML
    private void actionLoadFiles(ActionEvent event) {
        loadFiles(true);
    }

    @FXML
    private void actionAddFiles(ActionEvent event) {
        loadFiles(false);
    }

    private void loadFiles(boolean replaceExisting) {
        List<File> files = eu.griddigit.cimpal.main.util.ModelFactory.fileChooserCustom(
                false,
                "RDF files",
                List.of("*.rdf", "*.xml", "*.owl", "*.ttl", "*.n3", "*.nt", "*.nq", "*.trig",
                        "*.jsonld", "*.json", "*.rj", "*.trix", "*.zip"),
                "Select RDF files to visualise",
                "dialog.rdfVisualisation"
        );

        if (files == null || files.isEmpty()) {
            setStatus("No files were selected.");
            return;
        }

        List<File> selection = new ArrayList<>(files);
        setStatus("Loading " + selection.size() + " file(s)...");
        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);
        setLoadingControlsDisabled(true);

        Thread loader = new Thread(() -> {
            List<GraphEntry> loaded = new ArrayList<>();
            List<String> problems = new ArrayList<>();

            for (File file : selection) {
                try {
                    if (hasExtension(file.getName(), "zip")) {
                        loaded.addAll(readArchive(file, problems));
                    } else {
                        GraphEntry entry = readPlainFile(file);
                        if (entry == null) {
                            problems.add(file.getName() + ": unrecognised RDF syntax for this extension");
                        } else {
                            loaded.add(entry);
                        }
                    }
                } catch (Exception e) {
                    problems.add(file.getName() + ": " + describe(e));
                }
            }

            Platform.runLater(() -> {
                if (replaceExisting) {
                    graphEntries.clear();
                }
                graphEntries.addAll(loaded);
                setLoadingControlsDisabled(false);
                resetProgressBar();

                for (String problem : problems) {
                    System.out.println("[RDF Visualisation] " + problem);
                }
                lastLoadNote = problems.isEmpty()
                        ? ""
                        : " " + problems.size() + " input(s) could not be read - see the Output window.";

                if (loaded.isEmpty()) {
                    setStatus("Nothing could be loaded from the selected file(s)." + lastLoadNote);
                    return;
                }
                rebuildTree();
            });
        }, "rdf-visualisation-load");
        loader.setDaemon(true);
        loader.start();
    }

    private GraphEntry readPlainFile(File file) throws IOException {
        Lang lang = langFor(file.getName());
        if (lang == null) {
            return null;
        }
        String base = file.toURI().toString();
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = new FileInputStream(file)) {
            RDFDataMgr.read(model, in, base, lang);
        }
        return new GraphEntry(file.getName(), model, documentBase(model, base));
    }

    /**
     * Reads every RDF entry of an archive as its own named graph. Entries are parsed from the
     * archive stream and never written to disk, so no path traversal is possible here; the entry
     * count is still bounded because the archive is third-party input.
     */
    private List<GraphEntry> readArchive(File file, List<String> problems) throws IOException {
        List<GraphEntry> loaded = new ArrayList<>();

        try (ZipFile zip = new ZipFile(file)) {
            int seen = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (++seen > MAX_ZIP_ENTRIES) {
                    problems.add(file.getName() + ": archive exceeds the entry limit ("
                            + MAX_ZIP_ENTRIES + "); remaining entries skipped");
                    break;
                }

                Lang lang = langFor(entry.getName());
                if (lang == null) {
                    continue; // manifests, readmes and other non-RDF payload
                }

                String graphName = file.getName() + " ! " + entry.getName();
                String base = file.toURI() + "!/" + entry.getName();
                try (InputStream in = zip.getInputStream(entry)) {
                    Model model = ModelFactory.createDefaultModel();
                    RDFDataMgr.read(model, in, base, lang);
                    loaded.add(new GraphEntry(graphName, model, documentBase(model, base)));
                } catch (Exception e) {
                    problems.add(graphName + ": " + describe(e));
                }
            }
        }

        if (loaded.isEmpty()) {
            problems.add(file.getName() + ": the archive contains no recognised RDF entries");
        }
        return loaded;
    }

    /**
     * The syntax Jena should use for a name, or {@code null} when the extension is not an RDF one.
     * {@code .xml} is mapped explicitly: it is a legitimate RDF/XML extension for CGMES instance
     * data but is not registered as one, since XML in general is not RDF.
     */
    private static Lang langFor(String name) {
        Lang lang = RDFLanguages.filenameToLang(name);
        if (lang != null) {
            return lang;
        }
        return hasExtension(name, "xml") ? Lang.RDFXML : null;
    }

    private static boolean hasExtension(String name, String extension) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith("." + extension);
    }

    /**
     * The base against which relative URIs in this graph should be displayed. A document that
     * declares its own {@code xml:base} or {@code @base} normally also declares a prefix for it;
     * where it does not, the location the file was read from is the effective base.
     */
    private static String documentBase(Model model, String fallback) {
        String declared = model.getNsPrefixURI("");
        return declared != null && !declared.isBlank() ? declared : fallback;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    // ==================== graph ticking and highlighting ====================

    @FXML
    private void actionTickAllGraphs(ActionEvent event) {
        setAllGraphsTicked(true);
    }

    @FXML
    private void actionUntickAllGraphs(ActionEvent event) {
        setAllGraphsTicked(false);
    }

    private void setAllGraphsTicked(boolean ticked) {
        suppressRebuild = true;
        try {
            graphEntries.forEach(entry -> entry.selectedProperty().set(ticked));
        } finally {
            suppressRebuild = false;
        }
        // The tick boxes are not bound to the entries, so the visible rows need redrawing.
        lvGraphs.refresh();
        rebuildTree();
    }

    /** Unloads every highlighted graph. Ticking plays no part: it decides inclusion, not scope. */
    @FXML
    private void actionRemoveSelectedGraph(ActionEvent event) {
        // Copied first: the selection list is a live view and shrinks as the entries go.
        List<GraphEntry> highlighted = new ArrayList<>(lvGraphs.getSelectionModel().getSelectedItems());
        if (highlighted.isEmpty()) {
            GUIhelper.showWarning("No graph highlighted",
                    "Click a graph's name in the Named graphs list first - Ctrl or Shift for several - "
                            + "then press Remove selected.");
            return;
        }
        lvGraphs.getSelectionModel().clearSelection();
        graphEntries.removeAll(highlighted);
        setStatus(highlighted.size() == 1
                ? "Removed graph: " + highlighted.getFirst().name()
                : "Removed " + highlighted.size() + " graphs.");
        rebuildTree();
    }

    /**
     * Merges the ticked graphs into one called {@value #MERGED_GRAPH_NAME}, leaving the originals
     * loaded. The sources are unticked, because a merged graph shown alongside its own sources
     * would present every triple twice.
     */
    @FXML
    private void actionMergeGraphs(ActionEvent event) {
        List<GraphEntry> ticked = graphEntries.stream()
                .filter(GraphEntry::isSelected)
                .filter(entry -> !MERGED_GRAPH_NAME.equals(entry.name()))
                .toList();

        if (ticked.size() < 2) {
            GUIhelper.showWarning("Nothing to merge",
                    "Tick at least two graphs in the Named graphs list, then press Merge.");
            return;
        }

        Model merged = ModelFactory.createDefaultModel();
        for (GraphEntry entry : ticked) {
            merged.add(entry.model());
            // Later prefixes win, which matters only when two files disagree on one prefix.
            merged.setNsPrefixes(entry.model().getNsPrefixMap());
        }

        suppressRebuild = true;
        try {
            graphEntries.removeIf(entry -> MERGED_GRAPH_NAME.equals(entry.name()));
            ticked.forEach(entry -> entry.selectedProperty().set(false));
            graphEntries.add(new GraphEntry(MERGED_GRAPH_NAME, merged, ticked.getFirst().base()));
        } finally {
            suppressRebuild = false;
        }

        lvGraphs.refresh();
        setStatus("Merged " + ticked.size() + " graphs into \"" + MERGED_GRAPH_NAME + "\" ("
                + merged.size() + " triples). The source graphs are kept but unticked.");
        rebuildTree();
    }

    @FXML
    private void actionToggleGraphsPane(ActionEvent event) {
        graphsPaneVisible = !graphsPaneVisible;
        applyPaneVisibility();
    }

    @FXML
    private void actionToggleTreePane(ActionEvent event) {
        treePaneVisible = !treePaneVisible;
        applyPaneVisibility();
    }

    /**
     * Rebuilds the split from the two pane flags and relabels the buttons. The panes are taken
     * out of the SplitPane rather than hidden: an unmanaged SplitPane item keeps its division, so
     * hiding alone would leave the diagram no wider than before.
     */
    private void applyPaneVisibility() {
        List<javafx.scene.Node> panes = new ArrayList<>(3);
        if (graphsPaneVisible) {
            panes.add(graphsPane);
        }
        if (treePaneVisible) {
            panes.add(treePane);
        }
        panes.add(graphViewPane);
        visualisationSplitPane.getItems().setAll(panes);

        // The label states the action, so it flips once the pane is away.
        btnToggleGraphsPane.setText(graphsPaneVisible ? "Hide Named graphs" : "Show Named graphs");
        btnToggleTreePane.setText(treePaneVisible ? "Hide Graph tree" : "Show Graph tree");

        // setAll drops the divider positions, so restore a sensible split for the new item count.
        // Deferred, because a position set before the SplitPane has been laid out is discarded.
        Platform.runLater(() -> {
            if (panes.size() == 3) {
                visualisationSplitPane.setDividerPositions(0.22, 0.5);
            } else if (panes.size() == 2) {
                visualisationSplitPane.setDividerPositions(0.3);
            }
            graphView.fitToView();
        });
    }

    @FXML
    private void actionRedrawGraphView(ActionEvent event) {
        redrawGraphView();
    }

    @FXML
    private void actionZoomIn(ActionEvent event) {
        graphView.zoom(1.2);
    }

    @FXML
    private void actionZoomOut(ActionEvent event) {
        graphView.zoom(1 / 1.2);
    }

    @FXML
    private void actionFitGraphView(ActionEvent event) {
        graphView.fitToView();
    }

    /**
     * Redraws the diagram for the current filter result and the highlighted rows.
     * <p>
     * Highlighted graphs - rows of the list, or named-graph rows of the tree - set which graphs
     * are in scope; with none highlighted every filtered graph is. Any other highlighted tree row
     * is a focus resource, and the diagram is narrowed to those resources and their immediate
     * neighbours; a class folder contributes all of its instances.
     */
    private void redrawGraphView() {
        if (lastRows.isEmpty()) {
            graphView.clear();
            lblGraphViewInfo.setText("Load RDF to draw the relationships.");
            return;
        }

        Set<String> selectedGraphs = new LinkedHashSet<>();
        Set<String> focusNodes = new LinkedHashSet<>();

        for (GraphEntry entry : lvGraphs.getSelectionModel().getSelectedItems()) {
            if (entry != null) {
                selectedGraphs.add(entry.name());
            }
        }

        for (TreeItem<NodeValue> item : tvGraph.getSelectionModel().getSelectedItems()) {
            // A removed row can still be reported by the selection model for a moment.
            if (item == null || item.getValue() == null) {
                continue;
            }
            NodeValue value = item.getValue();
            if (value.kind() == NodeKind.GRAPH) {
                selectedGraphs.add(value.raw());
            } else if (value.kind() == NodeKind.CLASS) {
                focusNodes.addAll(value.members());
            } else if (!value.raw().isEmpty()) {
                focusNodes.add(value.raw());
            }
        }

        List<GraphRows> scope = selectedGraphs.isEmpty()
                ? lastRows
                : lastRows.stream().filter(row -> selectedGraphs.contains(row.name())).toList();

        List<RdfGraphView.Edge> edges = buildEdges(scope, focusNodes, cbIncludeLiterals.isSelected());
        RdfGraphView.Rendered rendered = graphView.show(edges, MAX_GRAPH_NODES);
        Platform.runLater(graphView::fitToView);

        StringBuilder info = new StringBuilder();
        if (rendered.nodeCount() == 0) {
            info.append("Nothing to draw: the highlighted rows have no relationships between resources.");
            if (!cbIncludeLiterals.isSelected()) {
                info.append(" Tick Include literals to see attribute values as nodes.");
            }
        } else {
            info.append(rendered.nodeCount()).append(" nodes, ")
                    .append(rendered.edgeCount()).append(" relationships");
            if (focusNodes.size() == 1) {
                info.append(" around the selected resource");
            } else if (!focusNodes.isEmpty()) {
                info.append(" around ").append(focusNodes.size()).append(" selected resources");
            } else if (selectedGraphs.size() == 1) {
                info.append(" in ").append(selectedGraphs.iterator().next());
            } else if (!selectedGraphs.isEmpty()) {
                info.append(" in ").append(selectedGraphs.size()).append(" graphs");
            }
            info.append('.');
            if (rendered.truncated()) {
                info.append(" Truncated at ").append(MAX_GRAPH_NODES)
                        .append(" nodes - narrow the filters or select a subject to see less at once.");
            }
        }
        lblGraphViewInfo.setText(info.toString());
    }

    /**
     * Turns filtered rows into diagram edges.
     *
     * @param focusNodes   when non-empty, keep only triples touching one of these resources, so
     *                     the diagram shows their neighbourhood rather than the whole graph
     * @param withLiterals include literal objects as their own nodes
     */
    private static List<RdfGraphView.Edge> buildEdges(
            List<GraphRows> scope, Set<String> focusNodes, boolean withLiterals) {

        List<RdfGraphView.Edge> edges = new ArrayList<>();
        for (GraphRows graph : scope) {
            for (SubjectRows subject : graph.subjects()) {
                String subjectId = subject.subject().raw();
                for (TripleRow triple : subject.triples()) {
                    Term object = triple.object();
                    if (!object.resource() && !withLiterals) {
                        continue;
                    }
                    if (!focusNodes.isEmpty()
                            && !focusNodes.contains(subjectId)
                            && !focusNodes.contains(object.raw())) {
                        continue;
                    }
                    // A literal is not identified by its value, so give each one its own node id;
                    // two resources sharing a value must not collapse into one node.
                    String objectId = object.resource()
                            ? object.raw()
                            : subjectId + " |" + triple.predicate().raw() + "| " + object.raw();
                    edges.add(new RdfGraphView.Edge(
                            subjectId, subject.subject().label(),
                            objectId, object.label(),
                            triple.predicate().label()));
                }
            }
        }
        return edges;
    }

    @FXML
    private void actionClearAll(ActionEvent event) {
        graphEntries.clear();
        clearFilterFields();
        lastRows = List.of();
        buildTreeItems();
        graphView.clear();
        lblGraphViewInfo.setText("Load RDF to draw the relationships.");
        lastLoadNote = "";
        resetProgressBar();
        setStatus("No data loaded. Use Load RDF files... to begin.");
    }

    // ==================== filtering ====================

    @FXML
    private void actionApplyFilters(ActionEvent event) {
        rebuildTree();
    }

    @FXML
    private void actionClearFilters(ActionEvent event) {
        clearFilterFields();
        rebuildTree();
    }

    private void clearFilterFields() {
        tfSubjectFilter.clear();
        tfPredicateFilter.clear();
        tfObjectFilter.clear();
        tfFullTextSearch.clear();
    }

    /**
     * Rescans the ticked graphs and rebuilds the tree. The scan runs off the FX thread and
     * produces plain data rows; the {@link TreeItem}s themselves are created on the FX thread,
     * lazily, as the user expands nodes.
     */
    private void rebuildTree() {
        if (suppressRebuild) {
            return;
        }
        List<GraphEntry> selected = graphEntries.stream().filter(GraphEntry::isSelected).toList();
        if (selected.isEmpty()) {
            lastRows = List.of();
            buildTreeItems();
            graphView.clear();
            lblGraphViewInfo.setText("No graph is ticked.");
            setStatus(graphEntries.isEmpty()
                    ? "No data loaded. Use Load RDF files... to begin."
                    : "No graph is ticked. Tick at least one graph in the list to see its triples.");
            return;
        }

        Filter filter = new Filter(
                text(tfSubjectFilter), text(tfPredicateFilter), text(tfObjectFilter), text(tfFullTextSearch));

        setStatus("Filtering...");
        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);
        btnApplyFilters.setDisable(true);

        Thread scanner = new Thread(() -> {
            List<GraphRows> rows = new ArrayList<>(selected.size());
            for (GraphEntry entry : selected) {
                rows.add(scan(entry, filter));
            }

            Platform.runLater(() -> {
                showRows(rows);
                btnApplyFilters.setDisable(false);
                resetProgressBar();
            });
        }, "rdf-visualisation-filter");
        scanner.setDaemon(true);
        scanner.start();
    }

    /** One pass over a graph, grouping the matching triples by subject in subject-label order. */
    private static GraphRows scan(GraphEntry entry, Filter filter) {
        Model model = entry.model();
        Map<String, SubjectRows> bySubject = new LinkedHashMap<>();
        int total = 0;
        int matched = 0;
        boolean truncated = false;

        StmtIterator it = model.listStatements();
        try {
            while (it.hasNext()) {
                Statement statement = it.nextStatement();
                total++;

                Term subject = term(statement.getSubject(), model, entry.base());
                Term predicate = term(statement.getPredicate(), model, entry.base());
                Term object = term(statement.getObject(), model, entry.base());

                if (!filter.accepts(subject, predicate, object)) {
                    continue;
                }
                matched++;

                SubjectRows rowsForSubject = bySubject.get(subject.raw());
                if (rowsForSubject == null) {
                    if (bySubject.size() >= MAX_SUBJECTS_PER_GRAPH) {
                        truncated = true;
                        continue;
                    }
                    rowsForSubject = new SubjectRows(subject, new ArrayList<>());
                    bySubject.put(subject.raw(), rowsForSubject);
                }
                rowsForSubject.triples().add(new TripleRow(predicate, object));
            }
        } finally {
            it.close();
        }

        List<SubjectRows> subjects = new ArrayList<>(bySubject.values());
        subjects.sort(Comparator.comparing(row -> row.subject().label(), String.CASE_INSENSITIVE_ORDER));
        subjects.forEach(row -> row.triples().sort(
                Comparator.comparing((TripleRow t) -> t.predicate().label(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(t -> t.object().label(), String.CASE_INSENSITIVE_ORDER)));

        return new GraphRows(entry.name(), subjects, matched, total, truncated);
    }

    private void showRows(List<GraphRows> rows) {
        lastRows = rows;
        buildTreeItems();
        setStatus(statusText(rows));
        redrawGraphView();
    }

    /**
     * Rebuilds the tree from {@link #lastRows} in the current grouping. Grouping is a reshaping
     * of rows that are already scanned and filtered, so this never touches the models - which is
     * why switching Group by needs no Apply.
     */
    private void buildTreeItems() {
        hierarchies.clear();
        // Cleared explicitly: the highlight cannot survive a reshape, and clearing it is what
        // widens the graph view's scope again.
        tvGraph.getSelectionModel().clearSelection();

        TreeItem<NodeValue> root = tvGraph.getRoot();
        root.getChildren().clear();
        if (lastRows.isEmpty()) {
            return;
        }

        // The named-graph level only earns its extra click when there is more than one graph.
        if (lastRows.size() == 1) {
            root.getChildren().setAll(groupedItems(lastRows.getFirst()));
        } else {
            List<TreeItem<NodeValue>> graphItems = new ArrayList<>(lastRows.size());
            for (GraphRows graph : lastRows) {
                String label = graph.name() + "  (" + graph.subjects().size() + " subject"
                        + (graph.subjects().size() == 1 ? "" : "s") + ", "
                        + graph.matched() + " of " + graph.total() + " triples)";
                graphItems.add(new LazyItem(NodeValue.graph(label, graph.name()),
                        () -> groupedItems(graph)));
            }
            root.getChildren().setAll(graphItems);
        }
    }

    /** The rows of one graph, shaped by the selected grouping. */
    private List<TreeItem<NodeValue>> groupedItems(GraphRows graph) {
        return switch (grouping()) {
            case FLAT -> subjectItems(graph);
            case CLASS -> classItems(graph);
            case CONTAINMENT, REFERENCES -> hierarchyItems(graph);
        };
    }

    private Grouping grouping() {
        Grouping selected = cbGrouping.getValue();
        return selected == null ? Grouping.FLAT : selected;
    }

    private static List<TreeItem<NodeValue>> subjectItems(GraphRows graph) {
        return subjectItems(graph.subjects(), graph.truncated());
    }

    private static List<TreeItem<NodeValue>> subjectItems(List<SubjectRows> subjects, boolean truncated) {
        List<TreeItem<NodeValue>> items = new ArrayList<>(subjects.size() + 1);
        for (SubjectRows subject : subjects) {
            String label = subject.subject().label() + "  (" + subject.triples().size() + ")";
            items.add(new LazyItem(NodeValue.subject(label, subject.subject().raw()),
                    () -> predicateItems(subject)));
        }
        if (truncated) {
            items.add(truncationItem());
        }
        return items;
    }

    /** A folder per {@code rdf:type}, holding its instances. A subject with two types is in both. */
    private static List<TreeItem<NodeValue>> classItems(GraphRows graph) {
        List<ClassGroup> groups = classGroups(graph.subjects());
        List<TreeItem<NodeValue>> items = new ArrayList<>(groups.size() + 1);
        for (ClassGroup group : groups) {
            List<SubjectRows> members = group.members();
            items.add(new LazyItem(classNodeValue(group), () -> subjectItems(members, false)));
        }
        if (graph.truncated()) {
            items.add(truncationItem());
        }
        return items;
    }

    private List<TreeItem<NodeValue>> hierarchyItems(GraphRows graph) {
        Hierarchy hierarchy = hierarchies.computeIfAbsent(graph.name(),
                key -> Hierarchy.of(graph, grouping() == Grouping.CONTAINMENT));
        List<TreeItem<NodeValue>> items = hierarchy.rootItems();
        if (graph.truncated()) {
            items.add(truncationItem());
        }
        return items;
    }

    /**
     * Groups subjects by the classes their own rows declare, folders sorted by name with the
     * untyped ones last. A subject with two {@code rdf:type} triples is a member of both folders.
     */
    private static List<ClassGroup> classGroups(List<SubjectRows> subjects) {
        Map<String, Term> types = new LinkedHashMap<>();
        Map<String, List<SubjectRows>> byType = new LinkedHashMap<>();
        List<SubjectRows> untyped = new ArrayList<>();

        for (SubjectRows subject : subjects) {
            Set<String> seen = new HashSet<>();
            for (TripleRow triple : subject.triples()) {
                if (!RDF.type.getURI().equals(triple.predicate().raw())) {
                    continue;
                }
                Term type = triple.object();
                if (!seen.add(type.raw())) {
                    continue; // the same type stated twice is still one folder
                }
                types.putIfAbsent(type.raw(), type);
                byType.computeIfAbsent(type.raw(), key -> new ArrayList<>()).add(subject);
            }
            if (seen.isEmpty()) {
                untyped.add(subject);
            }
        }

        List<ClassGroup> groups = new ArrayList<>(byType.size() + 1);
        byType.forEach((raw, members) -> groups.add(new ClassGroup(types.get(raw), members)));
        groups.sort(Comparator.comparing(group -> group.type().label(), String.CASE_INSENSITIVE_ORDER));
        if (!untyped.isEmpty()) {
            groups.add(new ClassGroup(new Term(NO_TYPE_LABEL, "", false), untyped));
        }
        return groups;
    }

    /** Carries the members, so highlighting a class folder draws that class's instances. */
    private static NodeValue classNodeValue(ClassGroup group) {
        return NodeValue.classNode(
                group.type().label() + "  (" + group.members().size() + ")",
                group.type().raw(),
                group.members().stream().map(member -> member.subject().raw()).toList());
    }

    private static TreeItem<NodeValue> truncationItem() {
        return new TreeItem<>(NodeValue.info(
                "... further subjects not shown - narrow the filters to reach them"));
    }

    private static List<TreeItem<NodeValue>> predicateItems(SubjectRows subject) {
        // Group by predicate, preserving the sorted order established in scan().
        Map<String, List<TripleRow>> byPredicate = new LinkedHashMap<>();
        for (TripleRow triple : subject.triples()) {
            byPredicate.computeIfAbsent(triple.predicate().raw(), key -> new ArrayList<>()).add(triple);
        }

        List<TreeItem<NodeValue>> items = new ArrayList<>(byPredicate.size());
        for (List<TripleRow> group : byPredicate.values()) {
            Term predicate = group.getFirst().predicate();
            if (group.size() == 1) {
                // Single-valued: one line rather than a node the user must open to see one child.
                Term object = group.getFirst().object();
                items.add(new TreeItem<>(NodeValue.value(
                        predicate.label() + "  →  " + object.label(), object.raw())));
            } else {
                TreeItem<NodeValue> predicateItem = new TreeItem<>(NodeValue.value(
                        predicate.label() + "  (" + group.size() + ")", predicate.raw()));
                for (TripleRow triple : group) {
                    predicateItem.getChildren().add(new TreeItem<>(
                            NodeValue.value(triple.object().label(), triple.object().raw())));
                }
                items.add(predicateItem);
            }
        }
        return items;
    }

    // ==================== tree controls ====================

    @FXML
    private void actionExpandSelected(ActionEvent event) {
        // Copied first: expanding a LazyItem replaces children, which the selection model reports.
        List<TreeItem<NodeValue>> highlighted =
                new ArrayList<>(tvGraph.getSelectionModel().getSelectedItems());
        List<TreeItem<NodeValue>> starts = highlighted.isEmpty()
                ? List.of(tvGraph.getRoot())
                : highlighted;

        // One budget shared across the highlighted branches, not one per branch.
        int expanded = 0;
        for (TreeItem<NodeValue> start : starts) {
            if (start == null) {
                continue;
            }
            expanded = expandRecursively(start, expanded);
            if (expanded >= MAX_EXPANDED_NODES) {
                break;
            }
        }
        if (expanded >= MAX_EXPANDED_NODES) {
            setStatus("Expanded the first " + MAX_EXPANDED_NODES
                    + " nodes of the selection; expand the remaining branches individually.");
        }
    }

    private static int expandRecursively(TreeItem<NodeValue> item, int alreadyExpanded) {
        if (alreadyExpanded >= MAX_EXPANDED_NODES) {
            return alreadyExpanded;
        }
        int count = alreadyExpanded;
        if (!item.isLeaf()) {
            item.setExpanded(true); // a LazyItem loads its children here
            count++;
            for (TreeItem<NodeValue> child : item.getChildren()) {
                count = expandRecursively(child, count);
                if (count >= MAX_EXPANDED_NODES) {
                    break;
                }
            }
        }
        return count;
    }

    @FXML
    private void actionCollapseAll(ActionEvent event) {
        tvGraph.getRoot().getChildren().forEach(RDFVisualisationController::collapseRecursively);
    }

    private static void collapseRecursively(TreeItem<NodeValue> item) {
        item.getChildren().forEach(RDFVisualisationController::collapseRecursively);
        item.setExpanded(false);
    }

    /** Copies the full value of every highlighted row, one per line. Structural rows carry none. */
    @FXML
    private void actionCopySelected(ActionEvent event) {
        List<String> values = tvGraph.getSelectionModel().getSelectedItems().stream()
                .filter(item -> item != null && item.getValue() != null)
                .map(item -> item.getValue().raw())
                .filter(raw -> !raw.isEmpty())
                .toList();
        if (values.isEmpty()) {
            setStatus("Highlight a row in the tree first, then press Copy value.");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(String.join(System.lineSeparator(), values));
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(values.size() == 1
                ? "Copied to clipboard: " + values.getFirst()
                : "Copied " + values.size() + " values to the clipboard, one per line.");
    }

    // ==================== term rendering ====================

    /**
     * Renders an RDF term for display and for matching. {@code label} is the shortened form shown
     * in the tree, {@code raw} the full URI or literal value - filters test both, so a search
     * works whether the user types a prefixed name or a full URI.
     */
    private static Term term(RDFNode node, Model model, String base) {
        if (node == null) {
            return new Term("", "", false);
        }
        if (node.isURIResource()) {
            String uri = node.asResource().getURI();
            return new Term(shorten(uri, model, base), uri, true);
        }
        if (node.isAnon()) {
            Resource resource = node.asResource();
            String id = "_:" + resource.getId().getLabelString();
            return new Term(id, id, true);
        }

        Literal literal = node.asLiteral();
        String lexical = literal.getLexicalForm();
        String language = literal.getLanguage();
        String datatype = literal.getDatatypeURI();

        String label;
        if (language != null && !language.isEmpty()) {
            label = '"' + lexical + "\"@" + language;
        } else if (datatype != null && !datatype.equals(XSDDatatype.XSDstring.getURI())) {
            label = '"' + lexical + "\"^^" + shorten(datatype, model, base);
        } else {
            label = '"' + lexical + '"';
        }
        return new Term(label, lexical, false);
    }

    private static String shorten(String uri, Model model, String base) {
        String prefixed = model.shortForm(uri);
        if (!prefixed.equals(uri)) {
            return prefixed;
        }
        // No prefix covers it: show it the way the source document does, relative to its base.
        if (base != null && !base.isEmpty() && uri.startsWith(base)) {
            String relative = uri.substring(base.length());
            if (!relative.isEmpty()) {
                return relative;
            }
        }
        return '<' + uri + '>';
    }

    // ==================== status and enablement ====================

    private void updateControlsEnabled() {
        boolean empty = graphEntries.isEmpty();
        btnRemoveGraph.setDisable(empty);
        btnClearAll.setDisable(empty);
        // Merging needs at least two graphs to merge.
        btnMergeGraphs.setDisable(graphEntries.size() < 2);
    }

    private void setLoadingControlsDisabled(boolean disabled) {
        btnApplyFilters.setDisable(disabled);
        lvGraphs.setDisable(disabled);
    }

    private String statusText(List<GraphRows> rows) {
        int matched = rows.stream().mapToInt(GraphRows::matched).sum();
        int total = rows.stream().mapToInt(GraphRows::total).sum();
        int subjects = rows.stream().mapToInt(row -> row.subjects().size()).sum();
        boolean truncated = rows.stream().anyMatch(GraphRows::truncated);

        StringBuilder text = new StringBuilder();
        text.append(rows.size()).append(rows.size() == 1 ? " graph, " : " graphs, ")
                .append(matched).append(" of ").append(total).append(" triples shown across ")
                .append(subjects).append(subjects == 1 ? " subject." : " subjects.");
        if (truncated) {
            text.append(" Truncated at ").append(MAX_SUBJECTS_PER_GRAPH)
                    .append(" subjects per graph - narrow the filters to see the rest.");
        }
        text.append(lastLoadNote);
        return text.toString();
    }

    private void setStatus(String message) {
        if (lblStatus != null) {
            lblStatus.setText(message);
        }
    }

    private static String text(TextField field) {
        String value = field == null || field.getText() == null ? "" : field.getText().trim();
        return value.toLowerCase(Locale.ROOT);
    }

    // ==================== supporting types ====================

    /**
     * How the tree arranges the filtered rows. All four are reshapings of the same rows, so
     * switching between them costs no rescan.
     */
    private enum Grouping {
        /** One alphabetical list of every resource. */
        FLAT("Subject (flat)"),
        /** A folder per class, holding its instances. */
        CLASS("Class (rdf:type)"),
        /** Nested by what points at a resource: Region, Substation, VoltageLevel, Bay, Equipment. */
        CONTAINMENT("Containment (incoming)"),
        /** Nested by what a resource points to: Terminal, Equipment, VoltageLevel, Substation. */
        REFERENCES("References (outgoing)");

        private final String label;

        Grouping(String label) {
            this.label = label;
        }

        /** Rendered by the ComboBox's default cell factory, which calls toString(). */
        @Override
        public String toString() {
            return label;
        }
    }

    /** An RDF term as shown ({@code label}) and as matched and copied ({@code raw}). */
    private record Term(String label, String raw, boolean resource) {
        boolean contains(String needle) {
            return label.toLowerCase(Locale.ROOT).contains(needle)
                    || raw.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    /** The four filter fields. All non-empty ones must match; empty ones are ignored. */
    private record Filter(String subject, String predicate, String object, String fullText) {
        boolean accepts(Term subjectTerm, Term predicateTerm, Term objectTerm) {
            if (!subject.isEmpty() && !subjectTerm.contains(subject)) {
                return false;
            }
            if (!predicate.isEmpty() && !predicateTerm.contains(predicate)) {
                return false;
            }
            if (!object.isEmpty() && !objectTerm.contains(object)) {
                return false;
            }
            return fullText.isEmpty()
                    || subjectTerm.contains(fullText)
                    || predicateTerm.contains(fullText)
                    || objectTerm.contains(fullText);
        }
    }

    private record TripleRow(Term predicate, Term object) {
    }

    private record SubjectRows(Term subject, List<TripleRow> triples) {
    }

    private record GraphRows(String name, List<SubjectRows> subjects, int matched, int total,
                             boolean truncated) {
    }

    /** One class folder: the type it stands for, and the subjects that declare it. */
    private record ClassGroup(Term type, List<SubjectRows> members) {
    }

    /**
     * What a tree row stands for. The graph view reads this to tell a named-graph row from a
     * resource row, which it used to guess by asking whether the row's value was also a graph
     * name.
     */
    private enum NodeKind { GRAPH, CLASS, SUBJECT, VALUE, INFO }

    /**
     * Value of one tree row: {@code label} is rendered, {@code raw} is what Copy value yields,
     * {@code kind} is how the graph view reads the row, and {@code members} carries the subjects
     * of a class folder so that highlighting the folder draws them.
     */
    private record NodeValue(String label, String raw, NodeKind kind, List<String> members) {

        static NodeValue graph(String label, String raw) {
            return new NodeValue(label, raw, NodeKind.GRAPH, List.of());
        }

        static NodeValue classNode(String label, String raw, List<String> members) {
            return new NodeValue(label, raw, NodeKind.CLASS, List.copyOf(members));
        }

        static NodeValue subject(String label, String raw) {
            return new NodeValue(label, raw, NodeKind.SUBJECT, List.of());
        }

        static NodeValue value(String label, String raw) {
            return new NodeValue(label, raw, NodeKind.VALUE, List.of());
        }

        /** A structural or explanatory row: nothing to copy, nothing to draw. */
        static NodeValue info(String label) {
            return new NodeValue(label, "", NodeKind.INFO, List.of());
        }

        /** Rendered by the TreeView's default cell factory, which calls toString(). */
        @Override
        public String toString() {
            return label == null ? "" : label;
        }
    }

    /**
     * A parent/child view over one graph's subjects, backing the two nesting grouping modes.
     * Built from rows that are already filtered, and cached per graph, so reshaping the tree
     * never goes back to the model.
     */
    private static final class Hierarchy {
        private final Map<String, SubjectRows> index;
        private final Map<String, Set<String>> children;
        private final List<String> roots;

        private Hierarchy(Map<String, SubjectRows> index, Map<String, Set<String>> children,
                          List<String> roots) {
            this.index = index;
            this.children = children;
            this.roots = roots;
        }

        /**
         * Builds the parent/child edges from the resource-valued triples of the given rows.
         *
         * @param containment nest by who points <em>at</em> a resource, which is what makes a
         *                    CGMES dataset read Region &#9656; Substation &#9656; VoltageLevel;
         *                    otherwise nest by what a resource points to
         */
        static Hierarchy of(GraphRows graph, boolean containment) {
            Map<String, SubjectRows> index = new LinkedHashMap<>();
            for (SubjectRows subject : graph.subjects()) {
                index.putIfAbsent(subject.subject().raw(), subject);
            }

            Map<String, Set<String>> children = new LinkedHashMap<>();
            Set<String> hasParent = new HashSet<>();
            for (SubjectRows subject : graph.subjects()) {
                String from = subject.subject().raw();
                for (TripleRow triple : subject.triples()) {
                    Term object = triple.object();
                    // Only a reference to another row of this graph can nest: a literal is a
                    // property, and a URI nothing here describes has no row to nest under.
                    if (!object.resource()
                            || object.raw().equals(from)
                            || !index.containsKey(object.raw())) {
                        continue;
                    }
                    String parent = containment ? object.raw() : from;
                    String child = containment ? from : object.raw();
                    // Two predicates can point at the same resource; it is still one child.
                    children.computeIfAbsent(parent, key -> new LinkedHashSet<>()).add(child);
                    hasParent.add(child);
                }
            }

            List<String> roots = new ArrayList<>();
            Set<String> reached = new HashSet<>();
            for (String raw : index.keySet()) {
                if (!hasParent.contains(raw)) {
                    roots.add(raw);
                    reach(raw, children, reached);
                }
            }
            // A mutual-reference pair or a longer cycle has no parentless member, so the walk
            // above never arrives at it. Each such component gets a root of its own, which is
            // what guarantees that nothing indexed is unreachable.
            for (String raw : index.keySet()) {
                if (!reached.contains(raw)) {
                    roots.add(raw);
                    reach(raw, children, reached);
                }
            }
            return new Hierarchy(index, children, roots);
        }

        /** Breadth-first walk from one root, marking everything below it as reached. */
        private static void reach(String start, Map<String, Set<String>> children, Set<String> reached) {
            if (!reached.add(start)) {
                return;
            }
            Deque<String> queue = new ArrayDeque<>();
            queue.addLast(start);
            while (!queue.isEmpty()) {
                for (String child : children.getOrDefault(queue.removeFirst(), Set.of())) {
                    if (reached.add(child)) {
                        queue.addLast(child);
                    }
                }
            }
        }

        List<TreeItem<NodeValue>> rootItems() {
            return items(roots, Set.of());
        }

        /**
         * Rows for a set of resources, grouped into class folders once there are more of them
         * than {@value RDFVisualisationController#HIERARCHY_CLASS_GROUP_THRESHOLD} - which is
         * what keeps a substation with forty children browsable. A single folder would only cost
         * a click, so it is skipped.
         */
        private List<TreeItem<NodeValue>> items(Collection<String> raws, Set<String> ancestors) {
            List<String> visible = visible(raws, ancestors);
            if (visible.size() <= HIERARCHY_CLASS_GROUP_THRESHOLD) {
                return plainItems(visible, ancestors);
            }

            List<ClassGroup> groups = classGroups(visible.stream().map(index::get).toList());
            if (groups.size() < 2) {
                return plainItems(visible, ancestors);
            }
            List<TreeItem<NodeValue>> items = new ArrayList<>(groups.size());
            for (ClassGroup group : groups) {
                List<String> members = group.members().stream()
                        .map(member -> member.subject().raw()).toList();
                // plainItems, not items: a folder must not regroup its own members for ever.
                items.add(new LazyItem(classNodeValue(group), () -> plainItems(members, ancestors)));
            }
            return items;
        }

        private List<TreeItem<NodeValue>> plainItems(List<String> raws, Set<String> ancestors) {
            List<TreeItem<NodeValue>> items = new ArrayList<>(raws.size());
            for (String raw : raws) {
                items.add(item(raw, ancestors));
            }
            return items;
        }

        /**
         * One resource: its nested resources first, then its own predicate rows. With nothing
         * nested the predicate rows are inlined, because a folder holding all of them would only
         * cost a click; with something nested they go behind a {@code properties} folder, so the
         * structure the mode exists to show stays legible.
         */
        private TreeItem<NodeValue> item(String raw, Set<String> ancestors) {
            SubjectRows subject = index.get(raw);
            List<String> nested = visible(children.getOrDefault(raw, Set.of()), ancestors);
            int properties = subject.triples().size();
            String label = subject.subject().label() + "  (" + properties
                    + (properties == 1 ? " property" : " properties")
                    + (nested.isEmpty() ? "" : ", " + nested.size() + " nested") + ")";

            if (nested.isEmpty()) {
                return new LazyItem(NodeValue.subject(label, raw), () -> predicateItems(subject));
            }
            // The ancestor path travels down with the supplier, and a child already on it is
            // skipped: without that a cycle would nest for ever. A resource shared by two parents
            // of a DAG still appears under both, which is what a reference view should show.
            Set<String> path = new LinkedHashSet<>(ancestors);
            path.add(raw);
            return new LazyItem(NodeValue.subject(label, raw), () -> {
                List<TreeItem<NodeValue>> rows = items(nested, path);
                rows.add(new LazyItem(NodeValue.value("properties (" + properties + ")", raw),
                        () -> predicateItems(subject)));
                return rows;
            });
        }

        private List<String> visible(Collection<String> raws, Set<String> ancestors) {
            List<String> visible = new ArrayList<>(raws.size());
            for (String raw : raws) {
                if (!ancestors.contains(raw)) {
                    visible.add(raw);
                }
            }
            return visible;
        }
    }

    /**
     * A tree node whose children are built the first time it is expanded. Without this, filtering
     * a large dataset would have to materialise every predicate and object row up front, most of
     * which the user never opens.
     */
    private static final class LazyItem extends TreeItem<NodeValue> {
        private final Supplier<List<TreeItem<NodeValue>>> childLoader;
        private boolean loaded;

        LazyItem(NodeValue value, Supplier<List<TreeItem<NodeValue>>> childLoader) {
            super(value);
            this.childLoader = childLoader;
            expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                if (isExpanded) {
                    load();
                }
            });
        }

        private void load() {
            if (loaded) {
                return;
            }
            loaded = true;
            getChildren().setAll(childLoader.get());
        }

        /**
         * Always a branch: LazyItem is only created for nodes known to have children, and the
         * disclosure arrow has to be drawn before those children exist.
         */
        @Override
        public boolean isLeaf() {
            return false;
        }
    }

    /** One loaded file or archive entry, with the parsed model and the base for display. */
    private static final class GraphEntry {
        private final String name;
        private final Model model;
        private final String base;
        private final long tripleCount;
        private final BooleanProperty selected = new SimpleBooleanProperty(true);

        GraphEntry(String name, Model model, String base) {
            this.name = name;
            this.model = model;
            this.base = base;
            this.tripleCount = model.size();
        }

        String name() {
            return name;
        }

        Model model() {
            return model;
        }

        String base() {
            return base;
        }

        long tripleCount() {
            return tripleCount;
        }

        BooleanProperty selectedProperty() {
            return selected;
        }

        boolean isSelected() {
            return selected.get();
        }
    }

    /** Graph list row: a tick box controlling inclusion in the tree, plus the triple count. */
    private final class GraphEntryCell extends ListCell<GraphEntry> {
        private final CheckBox checkBox = new CheckBox();
        private GraphEntry bound;

        private GraphEntryCell() {
            checkBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
                if (bound != null && bound.isSelected() != isSelected) {
                    bound.selectedProperty().set(isSelected);
                    // Ticking says nothing about which row is highlighted: moving the
                    // highlight here would silently change what the graph view draws.
                    rebuildTree();
                }
            });
            checkBox.setTooltip(new Tooltip("Include this graph in the tree and the graph view"));
        }

        @Override
        protected void updateItem(GraphEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            bound = null;
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            checkBox.setSelected(entry.isSelected());
            bound = entry;
            // The name is the cell's own text, not the checkbox's: a checkbox carrying the whole
            // label spans the row and consumes the click, so the row could never be selected and
            // "Remove selected" always found an empty selection.
            setText(entry.name() + "  (" + entry.tripleCount() + ")");
            setGraphic(checkBox);
        }
    }
}
