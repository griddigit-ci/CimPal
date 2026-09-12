/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.core.utils.SparqlTools;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.RdfGraphView;
import javafx.application.Platform;
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
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.AnonId;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javafx.stage.FileChooser;

/**
 * Controller for the <em>RDF Operations &#9656; Visualisation</em> tab.
 * <p>
 * Loads RDF in any syntax Jena has a parser for and presents it as a browsable
 * subject &rarr; predicate &rarr; object tree. Each loaded file (each entry, for ZIP archives)
 * becomes a named graph at the top of the unified browser, so one profile file of a CGMES
 * dataset can be inspected, merged, or removed without leaving the tree view.
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

    /**
     * Bound on the tree nodes one jump-to-reference search may visit. The nesting modes have to
     * materialise a level before they can look inside it, so the search gets a budget rather than
     * licence to walk an arbitrarily deep structure; exhausting it is reported rather than hidden.
     */
    private static final int MAX_NAVIGATION_VISITS = 50_000;

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

    /**
     * Prefix stood in for a namespace the source document declares no prefix for. A file-based
     * namespace is nearly all folder path - {@code file:///C:/Users/.../MicroGrid_EQ.xml#_1234} -
     * which pushes the part that identifies the resource off the end of the row; {@code loc:_1234}
     * says the same thing in a width that fits. A graph with a second such namespace gets
     * {@code loc2:}, a third {@code loc3:}, so two resources never share a label.
     */
    private static final String LOCAL_PREFIX = "loc";

    /**
     * Namespace length above which an invented prefix is worth it. The substitution exists to buy
     * width, so a namespace that already reads at a glance - {@code urn:uuid:} is nine characters,
     * and {@code loc:} would only make it anonymous - is left exactly as the document wrote it.
     */
    private static final int MIN_SHORTENED_NAMESPACE = 24;

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
    private TextArea taSparqlFilter;
    @FXML
    private Button btnApplyFilters;
    @FXML
    private Button btnRemoveGraph;
    @FXML
    private Button btnClearAll;
    @FXML
    private TreeView<NodeValue> tvGraph;
    @FXML
    private Label helpLoad;
    @FXML
    private Label helpFilters;
    @FXML
    private Label helpSparqlFilter;
    @FXML
    private Label helpTree;
    @FXML
    private Label helpPanes;
    @FXML
    private Label helpGraphView;
    @FXML
    private Button btnMergeGraphs;
    @FXML
    private Button btnToggleTreePane;
    @FXML
    private ComboBox<Grouping> cbGrouping;
    @FXML
    private SplitPane visualisationSplitPane;
    @FXML
    private VBox treePane;
    @FXML
    private VBox graphViewPane;
    @FXML
    private RdfGraphView graphView;
    @FXML
    private CheckBox cbIncludeLiterals;
    @FXML
    private ComboBox<RdfGraphView.LayoutMode> cbGraphLayout;
    @FXML
    private TableView<Map<String, String>> tvGraphData;
    @FXML
    private Button btnToggleGraphContent;
    @FXML
    private Button btnExportTable;
    @FXML
    private Button btnToggleGraphMetadata;

    private boolean tableViewVisible;

    private SparqlTools.QueryResults tableResults = new SparqlTools.QueryResults(List.of(), List.of());
    private String graphViewSummary = "";
    private String tableViewSummary = "";
    private boolean graphMetadataVisible;

    /** Loaded graphs in load order; their names form the top level of the graph browser. */
    private final ObservableList<GraphEntry> graphEntries = FXCollections.observableArrayList();

    /** Whether the unified graph browser is currently part of the split. */
    private boolean treePaneVisible = true;

    /** Appended to the status line: what the last load could not read. Empty when all was well. */
    private String lastLoadNote = "";

    /** Most recent status, retained until the parent controller is attached after FXML loading. */
    private String statusMessage = "";

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

    /**
     * Every shown subject by its full URI, across <em>all</em> shown graphs, so a reference row can
     * be resolved to the rows that describe it. Cross-graph deliberately: in a CGMES dataset a
     * Terminal declared in the EQ file is referenced from the SSH file constantly. Rebuilt with the
     * tree, first graph winning a collision.
     */
    private final Map<String, SubjectRows> subjectIndex = new HashMap<>();

    /**
     * Resolutions already worked out, misses included. Every resource-valued object row asks for
     * one as it is built, and a CGMES model asks the same questions over and over - every Terminal
     * points at the same VoltageLevel, and every {@code rdf:type} row names a class no instance
     * file describes. Depends on the models alone, so it is dropped only when the tree is rebuilt.
     */
    private final Map<String, Optional<Resolution>> resolutions = new HashMap<>();

    /** The steps a right-click retraces, most recent first. Cleared whenever the tree is reshaped. */
    private final Deque<NavStep> navigation = new ArrayDeque<>();

    /**
     * Subjects the scan materialises per graph. Starts at {@value #MAX_SUBJECTS_PER_GRAPH} and is
     * raised by that much again each time the show-more row is clicked; <em>Reset</em> puts it back.
     */
    private int subjectLimit = MAX_SUBJECTS_PER_GRAPH;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeHelpTooltips();

        tvGraph.setRoot(new TreeItem<>(NodeValue.info("")));
        tvGraph.setShowRoot(false);
        // A cell of our own, so a row can say what it is: the show-more row is drawn as an action
        // rather than as data, and the cell knows exactly which row a click landed on.
        tvGraph.setCellFactory(view -> new GraphTreeCell());
        // The secondary button is handled on the view rather than on the cell, so a right-click
        // anywhere in the pane retraces - there is no need to find the row you came from.
        tvGraph.setOnMouseClicked(this::handleTreeSecondaryClick);

        // The browser supports multi-selection, so several graphs or resources can be compared.
        tvGraph.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        cbGrouping.getItems().setAll(Grouping.values());
        cbGrouping.setValue(Grouping.FLAT);
        // Grouping reshapes rows that are already scanned and filtered, so it rebuilds the tree
        // without touching the models.
        cbGrouping.valueProperty().addListener((obs, oldValue, newValue) -> buildTreeItems());
        cbGraphLayout.getItems().setAll(RdfGraphView.LayoutMode.values());
        cbGraphLayout.setValue(RdfGraphView.LayoutMode.FORCE_DIRECTED);
        graphView.setLayoutMode(RdfGraphView.LayoutMode.FORCE_DIRECTED);

        graphEntries.addListener((ListChangeListener<GraphEntry>) change ->
                updateControlsEnabled());

        // Highlighting a named graph or a resource in the browser narrows the diagram to that scope.
        tvGraph.getSelectionModel().getSelectedItems()
                .addListener((ListChangeListener<TreeItem<NodeValue>>) change -> {
                    redrawGraphView();
                    refreshTable();
                });
        tvGraphData.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
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
        GUIhelper.installHelpTooltip(helpSparqlFilter,
                "Optional SPARQL SELECT filter. The query must project a ?s variable; subjects returned by it "
                        + "are kept in the tree and graph view. Apply runs it after the text filters, over the "
                        + "ticked graphs. The filtered graphs are available both as the default union graph and "
                        + "as named graphs, so GRAPH ?g { ... } is supported. Load query imports a .rq or .sparql file.");
        GUIhelper.installHelpTooltip(helpPanes,
                "The graph browser can be folded away to give the graph view the whole width. The button "
                        + "says what pressing it will do and turns into Show graph browser once the pane is away; "
                        + "the chevron in the pane header folds it in place.\n\n"
                        + "The browser is removed from the split rather than merely hidden, so the diagram "
                        + "actually gains the space.");
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
                "The graph browser starts with one named-graph row per loaded file (or archive entry); "
                        + "select one or more of these top-level rows to draw, merge, or remove those graphs. "
                        + "Each graph expands to its filtered triples, with every resource expanding to its predicates and objects. A predicate "
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
                        + "URIs are shortened using the namespace prefixes declared in the source file. Where no "
                        + "prefix applies and the namespace is long enough to crowd out the name - a file:/// "
                        + "namespace is mostly folder path - it is stood in for by loc:, so a row reads "
                        + "loc:_1234-5678. A second such namespace in the same graph becomes loc2:, a third "
                        + "loc3:. A short namespace, urn:uuid: for instance, is left as written.\n\n"
                        + "An object row that names a resource any loaded file describes carries a disclosure "
                        + "arrow: click it to open that resource's properties in place, and again on the rows "
                        + "below to trace a chain without losing your place. What opens is read from the models, "
                        + "not from the tree, so it reaches resources the filters left out and graphs that are "
                        + "not ticked, and where several files describe the resource it shows all of their "
                        + "triples together. Across CGMES profile files, which give the same object a different "
                        + "URI in each file, a reference falls back to matching on the local name.\n\n"
                        + "Double-click instead to jump to the resource's own row, where it has one. No arrow "
                        + "means nothing loaded describes it. One right-click steps back, a double right-click "
                        + "returns to where the trace started.\n\n"
                        + "Where a graph holds more subjects than the tree shows, its last row says so and "
                        + "clicking that row rescans for 5000 more.\n\n"
                        + "Copy value puts the full URI or literal of every highlighted row on the clipboard, "
                        + "one per line, and the filters match the full URI too.\n\n"
                        + "Highlighting rows also narrows the graph view to them.");
    }

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        if (!statusMessage.isBlank()) {
            mainController.setStatusMessage(statusMessage);
        }
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

    // ==================== named-graph actions ====================

    /** Unloads every selected named-graph row from the unified browser. */
    @FXML
    private void actionRemoveSelectedGraph(ActionEvent event) {
        // Copied first: the selection list is a live view and shrinks as the entries go.
        List<GraphEntry> highlighted = selectedGraphEntries();
        if (highlighted.isEmpty()) {
            GUIhelper.showWarning("No graph selected",
                    "Select one or more named graphs at the top of the graph browser first - Ctrl or Shift for several - "
                            + "then press Remove selected.");
            return;
        }
        tvGraph.getSelectionModel().clearSelection();
        graphEntries.removeAll(highlighted);
        setStatus(highlighted.size() == 1
                ? "Removed graph: " + highlighted.getFirst().name()
                : "Removed " + highlighted.size() + " graphs.");
        rebuildTree();
    }

    /**
     * Merges selected named graphs into one called {@value #MERGED_GRAPH_NAME}. The source
     * entries remain loaded and browsable; only a previous merged result is replaced.
     */
    @FXML
    private void actionMergeGraphs(ActionEvent event) {
        List<GraphEntry> ticked = selectedGraphEntries().stream()
                .filter(entry -> !MERGED_GRAPH_NAME.equals(entry.name()))
                .toList();

        if (ticked.size() < 2) {
            GUIhelper.showWarning("Nothing to merge",
                    "Select at least two named graphs in the graph browser, then press Merge.");
            return;
        }

        Model merged = ModelFactory.createDefaultModel();
        for (GraphEntry entry : ticked) {
            merged.add(entry.model());
            // Later prefixes win, which matters only when two files disagree on one prefix.
            merged.setNsPrefixes(entry.model().getNsPrefixMap());
        }

        graphEntries.removeIf(entry -> MERGED_GRAPH_NAME.equals(entry.name()));
        graphEntries.add(new GraphEntry(MERGED_GRAPH_NAME, merged, ticked.getFirst().base()));

        setStatus("Merged " + ticked.size() + " graphs into \"" + MERGED_GRAPH_NAME + "\" ("
                + merged.size() + " triples). Source graphs remain available in the browser.");
        rebuildTree();
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
        List<javafx.scene.Node> panes = new ArrayList<>(2);
        if (treePaneVisible) {
            panes.add(treePane);
        }
        panes.add(graphViewPane);
        visualisationSplitPane.getItems().setAll(panes);

        // The label states the action, so it flips once the pane is away.
        btnToggleTreePane.setText(treePaneVisible ? "Hide graph browser" : "Show graph browser");

        // setAll drops the divider positions, so restore a sensible split for the new item count.
        // Deferred, because a position set before the SplitPane has been laid out is discarded.
        Platform.runLater(() -> {
            if (panes.size() == 2) {
                visualisationSplitPane.setDividerPositions(1.0 / 3.0);
            }
            graphView.fitToView();
        });
    }

    @FXML
    private void actionRedrawGraphView(ActionEvent event) {
        redrawGraphView();
    }

    @FXML
    private void actionChangeGraphLayout(ActionEvent event) {
        graphView.setLayoutMode(cbGraphLayout.getValue());
        redrawGraphView();
    }

    @FXML
    private void actionToggleGraphMetadata(ActionEvent event) {
        graphMetadataVisible = !graphMetadataVisible;
        btnToggleGraphMetadata.setText(graphMetadataVisible ? "G✓" : "G");
        refreshTable();
    }

    /** Switches the graph content in-place instead of consuming vertical space with nested tabs. */
    @FXML
    private void actionToggleGraphContent(ActionEvent event) {
        tableViewVisible = !tableViewVisible;
        tvGraphData.setVisible(tableViewVisible);
        tvGraphData.setManaged(tableViewVisible);
        btnExportTable.setVisible(tableViewVisible);
        btnExportTable.setManaged(tableViewVisible);
        graphView.setVisible(!tableViewVisible);
        graphView.setManaged(!tableViewVisible);
        btnToggleGraphContent.setText(tableViewVisible ? "Graph view" : "Table view");
        if (tableViewVisible) {
            refreshTable();
        } else {
            Platform.runLater(graphView::fitToView);
        }
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

    /** Resolves selected top-level named-graph rows to their backing entries. */
    private List<GraphEntry> selectedGraphEntries() {
        Set<String> names = new LinkedHashSet<>();
        for (TreeItem<NodeValue> item : tvGraph.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null && item.getValue().kind() == NodeKind.GRAPH) {
                names.add(item.getValue().raw());
            }
        }
        return graphEntries.stream().filter(entry -> names.contains(entry.name())).toList();
    }

    /**
     * Redraws the diagram for the current filter result and the highlighted rows.
     * <p>
     * Highlighted named-graph rows set which graphs
     * are in scope; with none highlighted every filtered graph is. Any other highlighted tree row
     * is a focus resource, and the diagram is narrowed to those resources and their immediate
     * neighbours; a class folder contributes all of its instances.
     */
    private void redrawGraphView() {
        if (lastRows.isEmpty()) {
            graphView.clear();
            setStatus("Load RDF to draw the relationships.");
            return;
        }

        Set<String> selectedGraphs = new LinkedHashSet<>();
        Set<String> focusNodes = new LinkedHashSet<>();

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
        graphViewSummary = info.toString();
        updateViewStatus();
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

    /** Rebuilds the table from the current graph-browser selection or the optional SELECT query. */
    private void refreshTable() {
        if (tvGraphData == null) return;
        try {
            String query = taSparqlFilter.getText() == null ? "" : taSparqlFilter.getText().trim();
            if (!query.isBlank()) {
                Model union = ModelFactory.createDefaultModel();
                List<GraphEntry> scope = selectedGraphEntries();
                (scope.isEmpty() ? graphEntries : scope).forEach(entry -> union.add(entry.model()));
                tableResults = SparqlTools.executeSparqlQuery(query, union);
            } else {
                tableResults = treeRowsAsTable();
            }
            displayTableResults(tableResults);
        } catch (Exception ex) {
            tableResults = new SparqlTools.QueryResults(List.of(), List.of());
            tvGraphData.getColumns().clear();
            tvGraphData.getItems().clear();
            setStatus("Table query failed: " + describe(ex));
        }
    }

    /** Turns the selected tree scope into one row per subject, with predicates as columns. */
    private SparqlTools.QueryResults treeRowsAsTable() {
        Set<String> graphNames = new LinkedHashSet<>();
        Set<String> subjects = new LinkedHashSet<>();
        for (TreeItem<NodeValue> item : tvGraph.getSelectionModel().getSelectedItems()) {
            if (item == null || item.getValue() == null) continue;
            NodeValue value = item.getValue();
            if (value.kind() == NodeKind.GRAPH) graphNames.add(value.raw());
            if (value.kind() == NodeKind.SUBJECT || value.kind() == NodeKind.REFERENCE) subjects.add(value.raw());
            if (value.kind() == NodeKind.CLASS) subjects.addAll(value.members());
        }
        Map<String, Map<String, String>> rowsBySubject = new LinkedHashMap<>();
        Set<String> predicates = new LinkedHashSet<>();
        for (GraphRows graph : lastRows) {
            if (!graphNames.isEmpty() && !graphNames.contains(graph.name())) continue;
            for (SubjectRows subject : graph.subjects()) {
                if (!subjects.isEmpty() && !subjects.contains(subject.subject().raw())) continue;
                String rowKey = graphMetadataVisible
                        ? graph.name() + "\u0000" + subject.subject().raw()
                        : subject.subject().raw();
                Map<String, String> row = rowsBySubject.computeIfAbsent(rowKey, ignored -> {
                            Map<String, String> values = new LinkedHashMap<>();
                            values.put("Subject", subject.subject().label());
                            if (graphMetadataVisible) values.put("Graph", graph.name());
                            return values;
                        });
                for (TripleRow triple : subject.triples()) {
                    String predicate = triple.predicate().label();
                    predicates.add(predicate);
                    row.merge(predicate, triple.object().label(),
                            (first, next) -> first.equals(next) ? first : first + " | " + next);
                }
            }
        }
        List<String> columns = new ArrayList<>();
        columns.add("Subject");
        if (graphMetadataVisible) columns.add("Graph");
        columns.addAll(predicates);
        return new SparqlTools.QueryResults(columns, new ArrayList<>(rowsBySubject.values()));
    }

    private void displayTableResults(SparqlTools.QueryResults results) {
        tvGraphData.getColumns().clear();
        tvGraphData.getItems().clear();
        for (String name : results.columns) {
            TableColumn<Map<String, String>, String> column = new TableColumn<>(name);
            column.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                    data.getValue().getOrDefault(name, "")));
            column.setMinWidth(120);
            tvGraphData.getColumns().add(column);
        }
        tvGraphData.getItems().setAll(results.rows);
        tableViewSummary = (taSparqlFilter.getText() == null || taSparqlFilter.getText().isBlank()
                ? "Tree selection" : "SPARQL query") + ": " + results.rows.size() + " row(s), "
                + results.columns.size() + " column(s).";
        updateViewStatus();
    }

    @FXML
    private void actionExportTable(ActionEvent event) {
        if (tableResults.columns.isEmpty()) {
            GUIhelper.showWarning("Nothing to export", "Populate the table with a tree selection or a SELECT query first.");
            return;
        }
        try {
            File output = eu.griddigit.cimpal.main.util.ModelFactory.fileSaveCustom(
                    "Excel file", List.of("*.xlsx"), "Save graph table", "graph-results.xlsx");
            if (output == null) return;
            if (!output.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                output = new File(output.getAbsolutePath() + ".xlsx");
            }
            SparqlTools.exportResultsToExcel(tableResults, output);
            setStatus("Exported " + tableResults.rows.size() + " table row(s) to " + output.getName() + ".");
        } catch (Exception ex) {
            GUIhelper.showUserFriendlyError("Export failed", "Could not export the graph table to Excel.", ex);
        }
    }

    @FXML
    private void actionClearAll(ActionEvent event) {
        graphEntries.clear();
        clearFilterFields();
        subjectLimit = MAX_SUBJECTS_PER_GRAPH;
        lastRows = List.of();
        buildTreeItems();
        graphView.clear();
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
        taSparqlFilter.clear();
    }

    @FXML
    private void actionLoadSparqlQuery(ActionEvent event) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open SPARQL query");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("SPARQL query files", "*.rq", "*.sparql"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File queryFile = chooser.showOpenDialog(taSparqlFilter.getScene().getWindow());
        if (queryFile == null) return;
        try {
            taSparqlFilter.setText(Files.readString(queryFile.toPath(), StandardCharsets.UTF_8));
            setStatus("Loaded SPARQL filter from " + queryFile.getName() + ". Press Apply to execute it.");
        } catch (IOException ex) {
            GUIhelper.showUserFriendlyError("Query loading failed", "The SPARQL query file could not be opened.", ex);
        }
    }

    /**
     * Rescans the ticked graphs and rebuilds the tree. The scan runs off the FX thread and
     * produces plain data rows; the {@link TreeItem}s themselves are created on the FX thread,
     * lazily, as the user expands nodes.
     */
    private void rebuildTree() {
        List<GraphEntry> selected = List.copyOf(graphEntries);
        if (selected.isEmpty()) {
            lastRows = List.of();
            buildTreeItems();
            graphView.clear();
            setStatus(graphEntries.isEmpty()
                    ? "No data loaded. Use Load RDF files... to begin."
                    : "No graphs are available.");
            return;
        }

        Filter filter = new Filter(
                text(tfSubjectFilter), text(tfPredicateFilter), text(tfObjectFilter), text(tfFullTextSearch));
        String sparqlQuery = taSparqlFilter.getText() == null ? "" : taSparqlFilter.getText().trim();

        setStatus("Filtering...");
        setProgressBar(ProgressIndicator.INDETERMINATE_PROGRESS);
        btnApplyFilters.setDisable(true);

        // Captured before the scan starts, so a show-more click during one cannot change it midway.
        int limit = subjectLimit;
        // Every loaded graph, not only the ticked ones: a reference resolves against all of them,
        // so all of them need their local-name index, and building it here keeps the cost on the
        // thread that already shows a progress indicator instead of on a click.
        List<GraphEntry> loaded = new ArrayList<>(graphEntries);
        Thread scanner = new Thread(() -> {
            try {
                loaded.forEach(GraphEntry::warmLocalNames);
                Set<String> sparqlSubjects = sparqlQuery.isBlank() ? null
                        : executeSparqlSubjectFilter(sparqlQuery, selected, filter);
                List<GraphRows> rows = new ArrayList<>(selected.size());
                for (GraphEntry entry : selected) {
                    rows.add(scan(entry, filter, sparqlSubjects, limit));
                }

                Platform.runLater(() -> {
                    showRows(rows);
                    btnApplyFilters.setDisable(false);
                    resetProgressBar();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    btnApplyFilters.setDisable(false);
                    resetProgressBar();
                    setStatus("SPARQL filter failed: " + describe(ex));
                    GUIhelper.showUserFriendlyError("SPARQL filter failed",
                            "Use a SELECT query that returns a ?s subject variable.", ex);
                });
            }
        }, "rdf-visualisation-filter");
        scanner.setDaemon(true);
        scanner.start();
    }

    /** One pass over a graph, grouping the matching triples by subject in subject-label order. */
    private static GraphRows scan(GraphEntry entry, Filter filter, Set<String> sparqlSubjects, int subjectLimit) {
        Model model = entry.model();
        // The graph's own Labels, kept for its lifetime rather than made fresh per scan, so a
        // namespace it had to invent a prefix for keeps that prefix everywhere: in the rows, in
        // a row built later from the model, and in a status message about a resource.
        Labels labels = entry.labels();
        Map<String, SubjectRows> bySubject = new LinkedHashMap<>();
        int total = 0;
        int matched = 0;
        boolean truncated = false;

        StmtIterator it = model.listStatements();
        try {
            while (it.hasNext()) {
                Statement statement = it.nextStatement();
                total++;

                Term subject = term(statement.getSubject(), labels);
                Term predicate = term(statement.getPredicate(), labels);
                Term object = term(statement.getObject(), labels);

                if (!filter.accepts(subject, predicate, object)) {
                    continue;
                }
                if (sparqlSubjects != null && !sparqlSubjects.contains(subject.raw())) {
                    continue;
                }
                matched++;

                SubjectRows rowsForSubject = bySubject.get(subject.raw());
                if (rowsForSubject == null) {
                    if (bySubject.size() >= subjectLimit) {
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

    /** Runs a SELECT ?s query over the textual-filter result, as both union and named graphs. */
    private static Set<String> executeSparqlSubjectFilter(String queryText, List<GraphEntry> entries, Filter filter) {
        Query query = QueryFactory.create(queryText);
        if (!query.isSelectType() || !query.getResultVars().contains("s")) {
            throw new IllegalArgumentException("The visualisation SPARQL filter must be a SELECT query that returns ?s");
        }
        Dataset dataset = DatasetFactory.createTxnMem();
        Model union = ModelFactory.createDefaultModel();
        int graphNumber = 0;
        for (GraphEntry entry : entries) {
            Model filtered = ModelFactory.createDefaultModel();
            StmtIterator statements = entry.model().listStatements();
            try {
                while (statements.hasNext()) {
                    Statement statement = statements.nextStatement();
                    if (filter.accepts(term(statement.getSubject(), entry.labels()),
                            term(statement.getPredicate(), entry.labels()), term(statement.getObject(), entry.labels()))) {
                        filtered.add(statement);
                    }
                }
            } finally {
                statements.close();
            }
            dataset.addNamedModel("urn:cimpal:visualisation:graph:" + graphNumber++, filtered);
            union.add(filtered);
        }
        dataset.setDefaultModel(union);

        Set<String> subjects = new HashSet<>();
        try (QueryExecution execution = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = execution.execSelect();
            while (results.hasNext()) {
                QuerySolution solution = results.nextSolution();
                RDFNode subject = solution.get("s");
                if (subject != null && (subject.isURIResource() || subject.isAnon())) {
                    subjects.add(rawResourceId(subject));
                }
            }
        }
        return subjects;
    }

    private static String rawResourceId(RDFNode node) {
        return node.isURIResource() ? node.asResource().getURI()
                : "_:" + node.asResource().getId().getLabelString();
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
        // The steps hold TreeItems of the shape being replaced, so after a reshape they lead
        // nowhere: a Group by change or an Apply mid-trace starts the trace afresh.
        navigation.clear();
        // First graph wins a collision: one resource described in two profile files is still one
        // resource, and the earlier graph is the one loaded first.
        resolutions.clear();
        subjectIndex.clear();
        for (GraphRows graph : lastRows) {
            for (SubjectRows subject : graph.subjects()) {
                subjectIndex.putIfAbsent(subject.subject().raw(), subject);
            }
        }
        // Cleared explicitly: the highlight cannot survive a reshape, and clearing it is what
        // widens the graph view's scope again.
        tvGraph.getSelectionModel().clearSelection();

        TreeItem<NodeValue> root = tvGraph.getRoot();
        root.getChildren().clear();
        if (lastRows.isEmpty()) {
            return;
        }

        // The named-graph level only earns its extra click when there is more than one graph.
        {
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

    private List<TreeItem<NodeValue>> subjectItems(GraphRows graph) {
        return subjectItems(graph.subjects(), graph.truncated());
    }

    private List<TreeItem<NodeValue>> subjectItems(List<SubjectRows> subjects, boolean truncated) {
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
    private List<TreeItem<NodeValue>> classItems(GraphRows graph) {
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
                key -> new Hierarchy(graph, grouping() == Grouping.CONTAINMENT));
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

    /**
     * The last row of a truncated graph. Clicking it raises the cap and rescans, because the
     * subjects past the cap were never built - there is nothing sitting here to reveal.
     */
    private TreeItem<NodeValue> truncationItem() {
        return new TreeItem<>(NodeValue.more(String.format(
                "... showing the first %,d subjects - click to show %,d more",
                subjectLimit, MAX_SUBJECTS_PER_GRAPH)));
    }

    private List<TreeItem<NodeValue>> predicateItems(SubjectRows subject) {
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
                items.add(objectItem(predicate.label() + "  →  " + object.label(), object));
            } else {
                TreeItem<NodeValue> predicateItem = new TreeItem<>(NodeValue.value(
                        predicate.label() + "  (" + group.size() + ")", predicate.raw()));
                for (TripleRow triple : group) {
                    predicateItem.getChildren().add(
                            objectItem(triple.object().label(), triple.object()));
                }
                items.add(predicateItem);
            }
        }
        return items;
    }

    // ==================== resolving a reference against the models ====================

    /**
     * One graph that describes a resource, and the URI it describes it under - which is not
     * always the URI that was asked for, because a match may have been made on the local name.
     */
    private record Hit(GraphEntry graph, String subjectUri) {
    }

    /**
     * Where a reference leads: every loaded graph that describes the resource, and whether any of
     * them had to be matched on the local name rather than on the URI itself.
     */
    private record Resolution(List<Hit> hits, boolean byLocalName) {
    }

    /**
     * Every loaded graph that describes a resource, or {@code null} when none does.
     * <p>
     * The models are asked, not the tree rows: a resource the filters excluded, one past the
     * subject cap, and one in a graph that is not ticked are all still in memory, and a reference
     * to any of them can be followed. Ticking decides what the tree <em>lists</em>; it does not
     * decide what a reference is allowed to reach.
     * <p>
     * The URI is tried first. A graph that does not have it is then tried on the local name,
     * because CGMES profile files declare no {@code xml:base}: the very same Terminal is
     * {@code EQ.xml#_abc} in the EQ file and {@code SSH.xml#_abc} in the SSH file, so an exact
     * match alone would never cross from one profile to the next. Only graphs that lack the exact
     * URI are matched this way, so a file that does have the resource is never second-guessed.
     */
    private Resolution resolve(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return resolutions.computeIfAbsent(raw, key -> Optional.ofNullable(resolveUncached(key)))
                .orElse(null);
    }

    private Resolution resolveUncached(String raw) {
        List<Hit> hits = new ArrayList<>();
        List<GraphEntry> withoutExact = new ArrayList<>();
        for (GraphEntry entry : graphEntries) {
            if (describes(entry, raw)) {
                hits.add(new Hit(entry, raw));
            } else {
                withoutExact.add(entry);
            }
        }

        // A blank node is identified only within the file that declared it, so there is no local
        // name to carry it across a graph boundary.
        boolean byLocalName = false;
        String local = raw.startsWith("_:") ? null : localNameOf(raw);
        if (local != null) {
            for (GraphEntry entry : withoutExact) {
                String subject = entry.subjectByLocalName(local);
                if (subject != null) {
                    hits.add(new Hit(entry, subject));
                    byLocalName = true;
                }
            }
        }
        return hits.isEmpty() ? null : new Resolution(List.copyOf(hits), byLocalName);
    }

    /** Whether this graph states anything about the resource, asked of the model itself. */
    private static boolean describes(GraphEntry entry, String raw) {
        Model model = entry.model();
        return model.contains(resourceFor(model, raw), null, (RDFNode) null);
    }

    /** The resource a displayed value stands for, blank-node ids included. */
    private static Resource resourceFor(Model model, String raw) {
        return raw.startsWith("_:")
                ? model.createResource(AnonId.create(raw.substring(2)))
                : model.getResource(raw);
    }

    /** The part of a URI after its namespace, or {@code null} when it has none to match on. */
    private static String localNameOf(String uri) {
        int start = localNameStart(uri);
        return start < 0 ? null : uri.substring(start);
    }

    /**
     * Every triple the loaded graphs state about a resource, as display rows: the union across
     * graphs, so a Terminal followed from the SSH file arrives carrying its EQ name and type as
     * well as its SSH state. A triple stated identically by two graphs is listed once, and each
     * graph's terms are shortened with that graph's own prefixes.
     */
    private static SubjectRows modelRows(String raw, Resolution resolution) {
        Map<String, TripleRow> byTriple = new LinkedHashMap<>();
        Term subject = null;

        for (Hit hit : resolution.hits()) {
            Model model = hit.graph().model();
            Labels labels = hit.graph().labels();
            Resource resource = resourceFor(model, hit.subjectUri());
            if (subject == null) {
                subject = term(resource, labels);
            }
            StmtIterator it = model.listStatements(resource, null, (RDFNode) null);
            try {
                while (it.hasNext()) {
                    Statement statement = it.nextStatement();
                    Term predicate = term(statement.getPredicate(), labels);
                    Term object = term(statement.getObject(), labels);
                    byTriple.putIfAbsent(
                            predicate.raw() + '\u0000' + object.raw() + '\u0000' + object.resource(),
                            new TripleRow(predicate, object));
                }
            } finally {
                it.close();
            }
        }

        List<TripleRow> triples = new ArrayList<>(byTriple.values());
        triples.sort(Comparator.comparing((TripleRow t) -> t.predicate().label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(t -> t.object().label(), String.CASE_INSENSITIVE_ORDER));
        return new SubjectRows(subject == null ? new Term(raw, raw, true) : subject, triples);
    }

    /**
     * How a resolution reads on the status line: which graphs it drew on, and whether the local
     * name had to stand in for the URI - which is worth saying, because that is the one step that
     * assumes two files mean the same resource by the same name.
     */
    private static String describeResolution(Resolution resolution) {
        String graphs = String.join(", ",
                resolution.hits().stream().map(hit -> hit.graph().name()).toList());
        if (!resolution.byLocalName()) {
            return graphs;
        }
        return graphs + (resolution.hits().size() == 1
                ? " (matched on the local name)"
                : " (partly matched on the local name)");
    }

    /**
     * A row for one object, followable when a loaded model describes the resource it names.
     * <p>
     * A resolvable reference is a {@link LazyItem}, which is what makes following work in both
     * directions at once: the disclosure arrow itself says the row leads somewhere, expanding it
     * loads the target's predicate rows, and those rows' references are branches in turn - so a
     * chain can be traced as many levels as there are clicks. Recursion is safe because a
     * LazyItem builds nothing until it is opened, so a cycle costs one click per hop rather than
     * looping. An unresolvable reference stays a leaf, so the missing arrow says so before the
     * click.
     * <p>
     * What is loaded comes from the models, not from the scanned rows, so a filter, the subject
     * cap and an unticked graph all stop deciding what a reference may reach - only whether the
     * resource has a row of its own to jump to.
     */
    private TreeItem<NodeValue> objectItem(String label, Term object) {
        if (!object.resource()) {
            return new TreeItem<>(NodeValue.value(label, object.raw()));
        }
        NodeValue value = NodeValue.reference(label, object.raw(), object.label());
        Resolution resolution = resolve(object.raw());
        return resolution == null
                ? new TreeItem<>(value)
                : new LazyItem(value, () -> predicateItems(modelRows(object.raw(), resolution)));
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
        // Not through a reference: opening one loads the target's rows, whose own references
        // would be opened in turn, and "Expand selected" would chase them across the whole graph
        // until the budget ran out. Following a reference stays a deliberate click.
        if (item.getValue() != null && item.getValue().kind() == NodeKind.REFERENCE) {
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

    // ==================== following references ====================

    /**
     * Raises the subject cap and rescans. The subjects past the cap were never materialised, so
     * there is nothing here to reveal in place: this is an <em>Apply</em> with a higher limit, and
     * it collapses the tree exactly as <em>Apply</em> does. The raised cap holds until <em>Reset</em>.
     */
    private void showMoreSubjects() {
        subjectLimit += MAX_SUBJECTS_PER_GRAPH;
        setStatus(String.format("Rescanning with up to %,d subjects per graph...", subjectLimit));
        rebuildTree();
    }

    /**
     * Opens a reference row where it stands, so the target's properties appear beneath it and the
     * next reference down can be followed without leaving the row the trace started from.
     */
    private void followReference(TreeItem<NodeValue> item) {
        if (!(item instanceof LazyItem) || item.isExpanded()) {
            return;
        }
        navigation.push(new NavStep(
                new ArrayList<>(tvGraph.getSelectionModel().getSelectedItems()), item));
        item.setExpanded(true);
        tvGraph.scrollTo(tvGraph.getRow(item));
        setStatus("Opened " + item.getValue().display() + " here. Right-click to step back, "
                + "double right-click to return to the start of the trace.");
    }

    /**
     * Moves to the target's own node in the tree, wherever the current grouping put it, rather
     * than showing a copy of it under the row that referenced it.
     * <p>
     * Every URI the resource answers to is tried, not only the one written on the row: a
     * cross-profile reference names it under the referencing file's base, while its own row is
     * listed under the base of the file that declares it. Where the resource is in the models but
     * has no row - the filters exclude it, it fell past the subject cap, or its graph is not
     * ticked - there is nowhere to jump, so it is opened in place instead and the status line
     * says why.
     */
    private void jumpToReference(TreeItem<NodeValue> item) {
        NodeValue value = item.getValue();
        Resolution resolution = resolve(value.raw());
        if (resolution == null) {
            reportUnresolved(value);
            return;
        }

        List<TreeItem<NodeValue>> from =
                new ArrayList<>(tvGraph.getSelectionModel().getSelectedItems());
        for (String candidate : candidateUris(value.raw(), resolution)) {
            if (!subjectIndex.containsKey(candidate)) {
                continue;
            }
            TreeItem<NodeValue> target = locateSubject(candidate);
            if (target == null) {
                setStatus(value.display() + " has a row in the tree, but it could not be located in "
                        + "this grouping. Set Group by to Subject (flat), which always finds it.");
                return;
            }
            // Nothing to collapse on the way back: the jump opened the target's ancestors, and
            // folding them again would close parts of the tree the user may have opened.
            navigation.push(new NavStep(from, null));
            revealAndSelect(target);
            setStatus("Jumped to " + value.display() + ", described in "
                    + describeResolution(resolution)
                    + ". Right-click to step back, double right-click to return to the start.");
            return;
        }

        followReference(item);
        setStatus(value.display() + " is described in " + describeResolution(resolution)
                + ", but has no row of its own in the tree - the filters exclude it, it is past the "
                + "subject cap, or its graph is not ticked. It is shown under this row instead.");
    }

    /** Every URI the resource answers to: the one written on the row, then the ones it matched. */
    private static List<String> candidateUris(String raw, Resolution resolution) {
        List<String> candidates = new ArrayList<>();
        candidates.add(raw);
        for (Hit hit : resolution.hits()) {
            if (!candidates.contains(hit.subjectUri())) {
                candidates.add(hit.subjectUri());
            }
        }
        return candidates;
    }

    /**
     * The tree node that is this resource's own row, found by walking the real items rather than
     * by computing a path per grouping mode - there are four of them, and a class folder can sit
     * at an intermediate level of the nesting modes. Two rules keep the walk cheap and honest:
     * only the kinds that can <em>contain</em> a subject row are descended into, and a reference
     * row never is.
     * <p>
     * Cost follows the mode. {@code FLAT} matches at the first level with nothing loaded;
     * {@code CLASS} loads one level of folders; the nesting modes load level by level, bounded by
     * {@value #MAX_NAVIGATION_VISITS} visits.
     *
     * @return the row, or {@code null} when the budget ran out before it was found
     */
    private TreeItem<NodeValue> locateSubject(String uri) {
        boolean nesting = grouping() == Grouping.CONTAINMENT || grouping() == Grouping.REFERENCES;
        Deque<TreeItem<NodeValue>> queue = new ArrayDeque<>();
        queue.addLast(tvGraph.getRoot());
        int visits = 0;

        while (!queue.isEmpty() && visits++ < MAX_NAVIGATION_VISITS) {
            TreeItem<NodeValue> item = queue.removeFirst();
            NodeValue value = item.getValue();
            if (value == null) {
                continue;
            }
            if (value.kind() == NodeKind.SUBJECT && uri.equals(value.raw())) {
                return item;
            }
            boolean descend = switch (value.kind()) {
                case INFO, GRAPH, CLASS -> true;
                // Only in the nesting modes does a subject hold other subjects. In FLAT and CLASS
                // it holds predicate rows alone, and descending would mean loading five thousand
                // subjects' properties to find a row that was in the root's children all along.
                case SUBJECT -> nesting;
                // Never a reference: those are traces, not canonical locations. Following them
                // would let the search wander the whole graph, and it could "find" the target
                // inside somebody else's inline trace instead of at its own node.
                default -> false;
            };
            if (!descend) {
                continue;
            }
            if (item instanceof LazyItem lazy) {
                lazy.ensureLoaded();
            }
            queue.addAll(item.getChildren());
        }
        return null;
    }

    /** Opens every ancestor and the row itself, then selects it once the rows have been laid out. */
    private void revealAndSelect(TreeItem<NodeValue> target) {
        for (TreeItem<NodeValue> parent = target.getParent(); parent != null;
                parent = parent.getParent()) {
            parent.setExpanded(true);
        }
        target.setExpanded(true);
        // Deferred: a row index is only meaningful once the expansions above have taken effect.
        Platform.runLater(() -> {
            tvGraph.getSelectionModel().clearSelection();
            tvGraph.getSelectionModel().select(target);
            tvGraph.scrollTo(tvGraph.getRow(target));
        });
    }

    /**
     * Says that a reference leads nowhere. Now that resolution asks the models rather than the
     * rows, this is the one remaining case: no loaded graph states anything about the resource,
     * under its own URI or under its local name. Either the reference is genuinely dangling, or
     * the file that describes the resource has not been loaded.
     * <p>
     * The resource is named the way its row names it - the shortened {@code loc:} form - because
     * a full {@code file:///} URI is mostly folder path and would fill the status line with it.
     */
    private void reportUnresolved(NodeValue value) {
        setStatus(value.display() + " is referenced, but nothing loaded describes it"
                + (graphEntries.size() == 1 ? "" : " - in any of the " + graphEntries.size() + " loaded graphs")
                + ". Either the reference is dangling, or the file that describes it is not loaded."
                + " Its full URI is " + value.raw());
    }

    /**
     * Retracing, on the secondary button. Both counts fire on a double click - 1, then 2 - which is
     * exactly what is wanted here: the first pops one step and the second unwinds whatever is left,
     * so the two gestures compose without a disambiguation delay.
     */
    private void handleTreeSecondaryClick(MouseEvent event) {
        if (event.getButton() != MouseButton.SECONDARY) {
            return;
        }
        if (event.getClickCount() == 1) {
            navigateBack();
        } else if (event.getClickCount() == 2) {
            navigateToStart();
        }
    }

    private void navigateBack() {
        NavStep step = navigation.poll();
        if (step == null) {
            setStatus("Nothing to go back to.");
            return;
        }
        undo(step);
        setStatus(navigation.isEmpty()
                ? "Back at the start of the trace."
                : "Stepped back; " + navigation.size()
                        + (navigation.size() == 1 ? " step left." : " steps left."));
    }

    private void navigateToStart() {
        if (navigation.isEmpty()) {
            setStatus("Nothing to go back to.");
            return;
        }
        // The last one popped is the earliest, so its selection is the one that ends up restored.
        while (!navigation.isEmpty()) {
            undo(navigation.poll());
        }
        setStatus("Back at the start of the trace.");
    }

    /** Closes what the step opened, then puts the highlight back where it was. */
    private void undo(NavStep step) {
        if (step.collapseOnBack() != null) {
            step.collapseOnBack().setExpanded(false);
        }
        restoreSelection(step.selection());
    }

    /** Reselects the rows that are still in the tree; ones it has since dropped are skipped. */
    private void restoreSelection(List<TreeItem<NodeValue>> selection) {
        tvGraph.getSelectionModel().clearSelection();
        TreeItem<NodeValue> first = null;
        for (TreeItem<NodeValue> item : selection) {
            if (item == null || tvGraph.getRow(item) < 0) {
                continue;
            }
            tvGraph.getSelectionModel().select(item);
            if (first == null) {
                first = item;
            }
        }
        if (first != null) {
            tvGraph.scrollTo(tvGraph.getRow(first));
        }
    }

    // ==================== term rendering ====================

    /**
     * Renders an RDF term for display and for matching. {@code label} is the shortened form shown
     * in the tree, {@code raw} the full URI or literal value - filters test both, so a search
     * works whether the user types a prefixed name or a full URI.
     */
    private static Term term(RDFNode node, Labels labels) {
        if (node == null) {
            return new Term("", "", false);
        }
        if (node.isURIResource()) {
            String uri = node.asResource().getURI();
            return new Term(labels.shorten(uri), uri, true);
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
            label = '"' + lexical + "\"^^" + labels.shorten(datatype);
        } else {
            label = '"' + lexical + '"';
        }
        return new Term(label, lexical, false);
    }

    /**
     * Where a URI's namespace ends, split the way RDF splits it: after the last {@code #},
     * otherwise after the last {@code /}, otherwise after the last {@code :}.
     *
     * @return the index the local name starts at, or -1 when the URI has no local name to show
     */
    private static int localNameStart(String uri) {
        int start = Math.max(uri.lastIndexOf('#'),
                Math.max(uri.lastIndexOf('/'), uri.lastIndexOf(':'))) + 1;
        return start > 0 && start < uri.length() ? start : -1;
    }

    /**
     * Shortens URIs for display, inventing a prefix for any namespace the source document
     * declares none for. One instance per graph scan, because the invented prefixes are only
     * meaningful within the graph that produced them.
     */
    private static final class Labels {
        private final Model model;
        private final String base;
        /** Namespace to invented prefix, in order of first appearance. */
        private final Map<String, String> invented = new LinkedHashMap<>();

        Labels(Model model, String base) {
            this.model = model;
            this.base = base;
        }

        /**
         * Synchronized because one instance now serves the scan thread and the FX thread both:
         * the scan renders rows off the FX thread while a click can render a row or a message on
         * it, and the invented prefixes are assigned in order of first appearance.
         */
        synchronized String shorten(String uri) {
            String prefixed = model.shortForm(uri);
            if (!prefixed.equals(uri)) {
                return prefixed;
            }

            // No declared prefix covers it. When the namespace is long enough to crowd the local
            // name off the row it gets a prefix of its own; a short one is left alone. Only the
            // label changes: Term.raw stays the full URI, so the filters, Copy value and the
            // graph view's node identity are untouched.
            int start = localNameStart(uri);
            if (start >= MIN_SHORTENED_NAMESPACE) {
                return prefixFor(uri.substring(0, start)) + ':' + uri.substring(start);
            }

            // A bare namespace, with no local name to hang a prefix on: show it the way the
            // source document does, relative to its base.
            if (base != null && !base.isEmpty() && uri.startsWith(base)) {
                String relative = uri.substring(base.length());
                if (!relative.isEmpty()) {
                    return relative;
                }
            }
            return '<' + uri + '>';
        }

        /** loc for the first namespace of a graph, then loc2, loc3 - so labels stay distinct. */
        private String prefixFor(String namespace) {
            String prefix = invented.get(namespace);
            if (prefix == null) {
                prefix = invented.isEmpty() ? LOCAL_PREFIX : LOCAL_PREFIX + (invented.size() + 1);
                invented.put(namespace, prefix);
            }
            return prefix;
        }
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
        tvGraph.setDisable(disabled);
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
            text.append(String.format(" Truncated at %,d subjects per graph - click the row at the "
                    + "end of a truncated graph to show more, or narrow the filters.", subjectLimit));
        }
        text.append(lastLoadNote);
        return text.toString();
    }

    private void setStatus(String message) {
        statusMessage = message == null ? "" : message;
        if (mainController != null) {
            mainController.setStatusMessage(statusMessage);
        }
    }

    /** Combines the two view summaries in the application-wide bottom status bar. */
    private void updateViewStatus() {
        if (graphViewSummary.isBlank()) {
            setStatus(tableViewSummary);
        } else if (tableViewSummary.isBlank()) {
            setStatus(graphViewSummary);
        } else {
            setStatus(graphViewSummary + "  |  " + tableViewSummary);
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

    /** One reversible navigation step: what was selected, and what to re-collapse on the way back. */
    private record NavStep(List<TreeItem<NodeValue>> selection, TreeItem<NodeValue> collapseOnBack) {
    }

    /**
     * What a tree row stands for. The graph view reads this to tell a named-graph row from a
     * resource row, which it used to guess by asking whether the row's value was also a graph
     * name.
     */
    private enum NodeKind { GRAPH, CLASS, SUBJECT, VALUE, REFERENCE, MORE, INFO }

    /**
     * Value of one tree row: {@code label} is rendered, {@code raw} is what Copy value yields,
     * {@code kind} is how the graph view reads the row, {@code members} carries the subjects of a
     * class folder so that highlighting the folder draws them, and {@code display} is the term's
     * own shortened form for the rows that stand for a single term.
     */
    private record NodeValue(String label, String raw, NodeKind kind, List<String> members,
                             String display) {

        static NodeValue graph(String label, String raw) {
            return new NodeValue(label, raw, NodeKind.GRAPH, List.of(), label);
        }

        static NodeValue classNode(String label, String raw, List<String> members) {
            return new NodeValue(label, raw, NodeKind.CLASS, List.copyOf(members), label);
        }

        static NodeValue subject(String label, String raw) {
            return new NodeValue(label, raw, NodeKind.SUBJECT, List.of(), label);
        }

        static NodeValue value(String label, String raw) {
            return new NodeValue(label, raw, NodeKind.VALUE, List.of(), label);
        }

        /**
         * An object row whose object is a resource. Carries the target URI as its raw value, so
         * the graph view still treats it as a focus resource and Copy value still yields the URI,
         * and the object's own shortened form as {@code display}, so a message about the row can
         * name the resource the way the row does rather than by its full URI.
         */
        static NodeValue reference(String label, String raw, String display) {
            return new NodeValue(label, raw, NodeKind.REFERENCE, List.of(), display);
        }

        /** The row that raises the subject cap. No value, so it draws and copies nothing. */
        static NodeValue more(String label) {
            return new NodeValue(label, "", NodeKind.MORE, List.of(), label);
        }

        /** A structural or explanatory row: nothing to copy, nothing to draw. */
        static NodeValue info(String label) {
            return new NodeValue(label, "", NodeKind.INFO, List.of(), label);
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
    private final class Hierarchy {
        private final Map<String, SubjectRows> index;
        private final Map<String, Set<String>> children;
        private final List<String> roots;

        /**
         * Builds the parent/child edges from the resource-valued triples of the given rows.
         * <p>
         * The index stays per graph: nesting should not cross graph boundaries, only
         * <em>following</em> a reference should, which is what {@link #subjectIndex} is for.
         *
         * @param containment nest by who points <em>at</em> a resource, which is what makes a
         *                    CGMES dataset read Region &#9656; Substation &#9656; VoltageLevel;
         *                    otherwise nest by what a resource points to
         */
        Hierarchy(GraphRows graph, boolean containment) {
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
            this.index = index;
            this.children = children;
            this.roots = roots;
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
         * Materialises the children without expanding, so a search can look inside a node the
         * user has not opened - which is how a jump finds a row in a collapsed branch.
         */
        void ensureLoaded() {
            load();
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
        /** Shortening for this graph, shared by every scan and every later lookup. */
        private final Labels labels;
        /** Local name to the subject URI that carries it. Built on demand; see the accessor. */
        private Map<String, String> localNames;

        GraphEntry(String name, Model model, String base) {
            this.name = name;
            this.model = model;
            this.base = base;
            this.tripleCount = model.size();
            this.labels = new Labels(model, base);
        }

        Labels labels() {
            return labels;
        }

        /**
         * The subject of this graph whose URI ends in the given local name, or {@code null}.
         * <p>
         * This is what lets a reference cross from one CGMES profile file to the next, where the
         * same resource is {@code EQ.xml#_abc} in one file and {@code SSH.xml#_abc} in another.
         * The index costs one pass over the model, so it is built the first time it is wanted and
         * kept: {@link #warmLocalNames()} does that on the scan thread, where a pause is expected.
         * Synchronized because that thread builds it and the FX thread reads it.
         */
        synchronized String subjectByLocalName(String local) {
            if (localNames == null) {
                Map<String, String> index = new HashMap<>();
                ResIterator subjects = model.listSubjects();
                try {
                    while (subjects.hasNext()) {
                        Resource subject = subjects.nextResource();
                        String uri = subject.getURI();
                        if (uri == null) {
                            continue; // a blank node has no local name to be found by
                        }
                        String name = localNameOf(uri);
                        if (name != null) {
                            // First wins, so a graph that states the same local name twice - two
                            // namespaces sharing a name - resolves to the one it declared first.
                            index.putIfAbsent(name, uri);
                        }
                    }
                } finally {
                    subjects.close();
                }
                localNames = index;
            }
            return localNames.get(local);
        }

        /** Builds the local-name index if it is not built, so a later click does not pay for it. */
        void warmLocalNames() {
            subjectByLocalName("");
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

    }

    /**
     * Tree row rendering, and the primary-button gestures.
     * <p>
     * The primary button is handled in the cell rather than on the view because the cell knows
     * exactly which row was hit. Only the two action kinds are intercepted: every other row keeps
     * plain selection, which is what scopes the diagram.
     * <p>
     * JavaFX delivers count 1 and then count 2 on a double click, and no disambiguation delay is
     * imposed, so following is instant. A double click on a reference therefore opens the row in
     * place before it jumps - and the toolkit's own double-click branch toggle, which runs on the
     * second release, closes it again on the way past. Either way the extra step is on the
     * navigation stack, so a right-click undoes it.
     */
    private final class GraphTreeCell extends TreeCell<NodeValue> {

        private GraphTreeCell() {
            addEventFilter(MouseEvent.MOUSE_CLICKED, this::handleClick);
        }

        @Override
        protected void updateItem(NodeValue value, boolean empty) {
            super.updateItem(value, empty);
            // Cells are recycled, so the action styling has to come off a row that is no longer one.
            getStyleClass().remove("tree-action-row");
            if (empty || value == null) {
                setText(null);
                return;
            }
            setText(value.label());
            if (value.kind() == NodeKind.MORE) {
                getStyleClass().add("tree-action-row");
            }
        }

        private void handleClick(MouseEvent event) {
            if (event.getButton() != MouseButton.PRIMARY || isEmpty() || getItem() == null) {
                return;
            }
            NodeValue value = getItem();
            if (value.kind() == NodeKind.MORE) {
                if (event.getClickCount() == 1) {
                    event.consume();
                    showMoreSubjects();
                }
                return;
            }
            if (value.kind() != NodeKind.REFERENCE) {
                return;
            }

            // A click on the arrow has already expanded or collapsed the row, and following it
            // as well would reopen a row the user has just closed. The arrow is expand/collapse
            // alone; the rest of the row is the gesture.
            if (onDisclosureNode(event)) {
                return;
            }

            TreeItem<NodeValue> item = getTreeItem();
            // Only a resolvable reference was built as a LazyItem, which is the same thing the
            // disclosure arrow tells the user.
            boolean resolvable = item instanceof LazyItem;
            if (event.getClickCount() == 1) {
                // Silent when it cannot be followed: a row selected only to scope the diagram
                // should not nag about a reference nobody asked to follow.
                if (resolvable) {
                    followReference(item);
                }
            } else if (event.getClickCount() == 2) {
                // Consumed: the gesture is handled here, and nothing above the cell should act on
                // it as well.
                event.consume();
                if (resolvable) {
                    jumpToReference(item);
                } else {
                    reportUnresolved(value);
                }
            }
        }

        /** The same bounds test the toolkit uses to decide that a click was on the arrow. */
        private boolean onDisclosureNode(MouseEvent event) {
            javafx.scene.Node disclosure = getDisclosureNode();
            return disclosure != null
                    && disclosure.getBoundsInParent().contains(event.getX(), event.getY());
        }
    }

}
