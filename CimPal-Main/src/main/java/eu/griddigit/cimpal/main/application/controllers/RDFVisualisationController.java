/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
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

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
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

    /** Loaded graphs in load order; the ListView is a view onto this list. */
    private final ObservableList<GraphEntry> graphEntries = FXCollections.observableArrayList();

    /**
     * Set while a bulk tick change is in progress, so Select all / Select none rebuild the tree
     * once at the end instead of once per row.
     */
    private boolean suppressRebuild;

    /** Appended to the status line: what the last load could not read. Empty when all was well. */
    private String lastLoadNote = "";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        initializeHelpTooltips();

        lvGraphs.setItems(graphEntries);
        lvGraphs.setCellFactory(view -> new GraphEntryCell());

        tvGraph.setRoot(new TreeItem<>(new NodeValue("", "")));
        tvGraph.setShowRoot(false);

        graphEntries.addListener((javafx.collections.ListChangeListener<GraphEntry>) change ->
                updateControlsEnabled());
        updateControlsEnabled();
        setStatus("No data loaded. Use Load RDF files... to begin.");
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
                        + "Untick a graph to leave it out of the tree without unloading it. When more than one "
                        + "graph is ticked the tree gets a named-graph level above the subjects; with a single "
                        + "graph the subjects are shown directly.");
        GUIhelper.installHelpTooltip(helpTree,
                "Subjects of the filtered triples, each expanding to its predicates and objects. A predicate "
                        + "with one value is shown on a single line; a repeated predicate becomes a node with one "
                        + "child per value.\n\n"
                        + "URIs are shortened using the namespace prefixes declared in the source file, or written "
                        + "relative to the document base where no prefix applies. Copy value puts the full URI or "
                        + "literal of the selected row on the clipboard.");
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

    // ==================== graph selection ====================

    @FXML
    private void actionSelectAllGraphs(ActionEvent event) {
        setAllGraphsSelected(true);
    }

    @FXML
    private void actionSelectNoGraphs(ActionEvent event) {
        setAllGraphsSelected(false);
    }

    private void setAllGraphsSelected(boolean selected) {
        suppressRebuild = true;
        try {
            graphEntries.forEach(entry -> entry.selectedProperty().set(selected));
        } finally {
            suppressRebuild = false;
        }
        // The tick boxes are not bound to the entries, so the visible rows need redrawing.
        lvGraphs.refresh();
        rebuildTree();
    }

    @FXML
    private void actionRemoveSelectedGraph(ActionEvent event) {
        GraphEntry highlighted = lvGraphs.getSelectionModel().getSelectedItem();
        if (highlighted == null) {
            setStatus("Highlight a graph in the list first, then press Remove selected.");
            return;
        }
        graphEntries.remove(highlighted);
        rebuildTree();
    }

    @FXML
    private void actionClearAll(ActionEvent event) {
        graphEntries.clear();
        clearFilterFields();
        tvGraph.getRoot().getChildren().clear();
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
            tvGraph.getRoot().getChildren().clear();
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
        TreeItem<NodeValue> root = tvGraph.getRoot();
        root.getChildren().clear();

        // The named-graph level only earns its extra click when there is more than one graph.
        if (rows.size() == 1) {
            root.getChildren().setAll(subjectItems(rows.getFirst()));
        } else {
            List<TreeItem<NodeValue>> graphItems = new ArrayList<>(rows.size());
            for (GraphRows graph : rows) {
                String label = graph.name() + "  (" + graph.subjects().size() + " subject"
                        + (graph.subjects().size() == 1 ? "" : "s") + ", "
                        + graph.matched() + " of " + graph.total() + " triples)";
                graphItems.add(new LazyItem(new NodeValue(label, graph.name()),
                        () -> subjectItems(graph)));
            }
            root.getChildren().setAll(graphItems);
        }

        setStatus(statusText(rows));
    }

    private static List<TreeItem<NodeValue>> subjectItems(GraphRows graph) {
        List<TreeItem<NodeValue>> items = new ArrayList<>(graph.subjects().size());
        for (SubjectRows subject : graph.subjects()) {
            String label = subject.subject().label() + "  (" + subject.triples().size() + ")";
            items.add(new LazyItem(new NodeValue(label, subject.subject().raw()),
                    () -> predicateItems(subject)));
        }
        if (graph.truncated()) {
            items.add(new TreeItem<>(new NodeValue(
                    "... further subjects not shown - narrow the filters to reach them", "")));
        }
        return items;
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
                items.add(new TreeItem<>(new NodeValue(
                        predicate.label() + "  →  " + object.label(), object.raw())));
            } else {
                TreeItem<NodeValue> predicateItem = new TreeItem<>(new NodeValue(
                        predicate.label() + "  (" + group.size() + ")", predicate.raw()));
                for (TripleRow triple : group) {
                    predicateItem.getChildren().add(new TreeItem<>(
                            new NodeValue(triple.object().label(), triple.object().raw())));
                }
                items.add(predicateItem);
            }
        }
        return items;
    }

    // ==================== tree controls ====================

    @FXML
    private void actionExpandSelected(ActionEvent event) {
        TreeItem<NodeValue> selected = tvGraph.getSelectionModel().getSelectedItem();
        TreeItem<NodeValue> start = selected != null ? selected : tvGraph.getRoot();
        int expanded = expandRecursively(start, 0);
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

    @FXML
    private void actionCopySelected(ActionEvent event) {
        TreeItem<NodeValue> selected = tvGraph.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null || selected.getValue().raw().isEmpty()) {
            setStatus("Select a row in the tree first, then press Copy value.");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(selected.getValue().raw());
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("Copied to clipboard: " + selected.getValue().raw());
    }

    // ==================== term rendering ====================

    /**
     * Renders an RDF term for display and for matching. {@code label} is the shortened form shown
     * in the tree, {@code raw} the full URI or literal value - filters test both, so a search
     * works whether the user types a prefixed name or a full URI.
     */
    private static Term term(RDFNode node, Model model, String base) {
        if (node == null) {
            return new Term("", "");
        }
        if (node.isURIResource()) {
            String uri = node.asResource().getURI();
            return new Term(shorten(uri, model, base), uri);
        }
        if (node.isAnon()) {
            Resource resource = node.asResource();
            String id = "_:" + resource.getId().getLabelString();
            return new Term(id, id);
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
        return new Term(label, lexical);
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

    /** An RDF term as shown ({@code label}) and as matched and copied ({@code raw}). */
    private record Term(String label, String raw) {
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

    /** Value of one tree row: {@code label} is rendered, {@code raw} is what Copy value yields. */
    private record NodeValue(String label, String raw) {
        @Override
        public String toString() {
            return label;
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
                    rebuildTree();
                }
            });
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
            checkBox.setText(entry.name() + "  (" + entry.tripleCount() + ")");
            setText(null);
            setGraphic(checkBox);
        }
    }
}
