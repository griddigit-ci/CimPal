/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.shacl_tools.ShapeConfigurationMerger;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationConfig;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.Candidate;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.Match;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.MatchKind;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.Plan;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.ShapeInfo;
import eu.griddigit.cimpal.core.shacl_tools.ShapeDeactivationPlanner.SourceModel;
import eu.griddigit.cimpal.main.gui.GUIhelper;
import eu.griddigit.cimpal.main.gui.GuiDialogs;
import eu.griddigit.cimpal.main.gui.PathMemory;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.jena.shared.PrefixMapping;
import org.topbraid.shacl.vocabulary.SH;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The review window of the deactivation configuration: the base shapes the planner listed, ticked
 * when it suggests deactivating them, a picker to add any other base shape, the IRI each
 * {@code owl:imports} names (a file URI by default; paste the published URL instead), and the
 * configuration's header. Saving writes the configuration with {@link ShapeDeactivationConfig}.
 */
final class ShaclDeactivationDialog {

    private static final String ADDED_BY_HAND = "Added by hand";

    /** A base shape in the review table. */
    static final class ShapeRow {
        final ShapeInfo shape;
        final BooleanProperty selected;
        final String status;
        final String reasons;
        final String replacement;

        ShapeRow(ShapeInfo shape, boolean selected, String status, String reasons, String replacement) {
            this.shape = shape;
            this.selected = new SimpleBooleanProperty(selected);
            this.status = status;
            this.reasons = reasons;
            this.replacement = replacement;
        }
    }

    /** A file the configuration imports, and the IRI the import names. */
    static final class ImportRow {
        final File file;
        final String role;
        final StringProperty iri;

        ImportRow(File file, String role) {
            this.file = file;
            this.role = role;
            this.iri = new SimpleStringProperty(file.toURI().toString());
        }

        /** What is wrong with the IRI, a warning about it, or empty. */
        String status() {
            String problem = ShapeDeactivationConfig.importProblem(iri.get());
            if (problem != null) {
                return "Not usable: " + problem;
            }
            String value = iri.get().trim().toLowerCase(Locale.ROOT);
            if ((value.startsWith("http://") || value.startsWith("https://"))
                    && !ShapeDeactivationConfig.isFetchableWebImport(value)) {
                return "Warning: CimPal only downloads web imports that end in .ttl or .rdf; this one would be skipped";
            }
            return value.startsWith("file:") ? "Local file" : "OK";
        }
    }

    private final Stage stage = new Stage();
    private final ObservableList<ShapeRow> rows = FXCollections.observableArrayList(
            row -> new javafx.beans.Observable[]{row.selected});
    private final ObservableList<ImportRow> imports = FXCollections.observableArrayList();
    private final List<ShapeInfo> baseShapes;
    private final PrefixMapping prefixes = PrefixMapping.Factory.create();
    private final Consumer<String> output;
    private final Label summary = new Label();

    private final TextField tfIri = new TextField(PathMemory.recallValue("tab.deactivation.configIri", ""));
    private final TextField tfPrefix = new TextField(PathMemory.recallValue("tab.deactivation.configPrefix", "config"));
    private final TextField tfTitle = new TextField(PathMemory.recallValue("tab.deactivation.configTitle", "Configuration Constraints"));
    private final TextField tfDescription = new TextField();
    private final TextField tfVersion = new TextField();
    private final CheckBox cbComments = new CheckBox("Write the reason as a comment above each deactivated shape");
    private final CheckBox cbMerged = new CheckBox("Also write the merged constraint file next to the configuration "
            + "(<name>-merged.ttl): the imported files in one file, without the deactivated shapes");
    private final TextField tfMergedBase = new TextField(PathMemory.recallValue("tab.deactivation.mergedBase", ""));

    ShaclDeactivationDialog(Plan plan, List<File> baseFiles, List<File> overrideFiles, List<SourceModel> baseModels,
                            Consumer<String> output) {
        this.baseShapes = plan.baseShapes();
        this.output = output;
        for (SourceModel model : baseModels) {
            model.model().getNsPrefixMap().forEach((prefix, namespace) -> {
                if (prefixes.getNsPrefixURI(prefix) == null) {
                    prefixes.setNsPrefix(prefix, namespace);
                }
            });
        }
        for (Candidate candidate : plan.candidates()) {
            rows.add(toRow(candidate));
        }
        baseFiles.forEach(file -> imports.add(new ImportRow(file, "Base")));
        overrideFiles.forEach(file -> imports.add(new ImportRow(file, "Replacing")));
        tfDescription.setText("Imports " + baseFiles.stream().map(File::getName).collect(Collectors.joining(", "))
                + " and " + overrideFiles.stream().map(File::getName).collect(Collectors.joining(", "))
                + "; deactivates the base constraints the latter replace.");

        stage.setTitle("Deactivation configuration");
        stage.initModality(Modality.APPLICATION_MODAL);
        VBox root = new VBox(8, header(plan), shapeTable(), importSection(), actions());
        root.setPadding(new Insets(12));
        stage.setScene(new Scene(root, 1150, 820));
        updateSummary();
    }

    void showAndWait() {
        stage.showAndWait();
    }

    private ShapeRow toRow(Candidate candidate) {
        List<Match> matches = candidate.matches();
        String status;
        if (matches.stream().anyMatch(match -> match.kind() == MatchKind.SAME_IRI)) {
            status = "Same IRI - check";
        } else if (candidate.suggested()) {
            status = "Suggested";
        } else if (matches.stream().allMatch(match -> match.kind() == MatchKind.RELATED)) {
            status = "Related";
        } else {
            status = "For information";
        }
        Map<MatchKind, List<Match>> byKind = new LinkedHashMap<>();
        matches.forEach(match -> byKind.computeIfAbsent(match.kind(), k -> new ArrayList<>()).add(match));
        List<String> parts = new ArrayList<>();
        byKind.forEach((kind, list) -> parts.add(list.size() == 1
                ? kind.label() + ": " + list.getFirst().detail()
                : kind.label() + ": " + list.size() + " rules"));
        String replacement = matches.stream()
                .filter(match -> match.overrideIri() != null && match.kind() != MatchKind.SAME_IRI)
                .map(match -> shortName(match.overrideIri()))
                .distinct()
                .collect(Collectors.joining(", "));
        return new ShapeRow(candidate.shape(), candidate.suggested(), status, String.join("; ", parts), replacement);
    }

    // ---- layout -------------------------------------------------------------

    private VBox header(Plan plan) {
        Label intro = new Label("Ticked shapes are deactivated in the configuration. Untick what should stay active, "
                + "and use Add constraints to deactivate base shapes that are not listed. Hover over a row for the "
                + "full reasons and the shape's description.");
        intro.setWrapText(true);
        VBox box = new VBox(4, intro, summary);
        if (!plan.warnings().isEmpty()) {
            Label warnings = new Label("Warnings:\n- " + String.join("\n- ", plan.warnings()));
            warnings.setWrapText(true);
            box.getChildren().add(warnings);
        }
        return box;
    }

    private VBox shapeTable() {
        TextField filter = new TextField();
        filter.setPromptText("Filter by IRI, name, status or reason");
        CheckBox onlyTicked = new CheckBox("Only ticked");
        FilteredList<ShapeRow> filtered = new FilteredList<>(rows, row -> true);
        Runnable refilter = () -> {
            String text = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
            boolean ticked = onlyTicked.isSelected();
            filtered.setPredicate(row -> (!ticked || row.selected.get()) && (text.isEmpty()
                    || (row.shape.iri() + " " + row.shape.name() + " " + row.status + " " + row.reasons + " "
                    + row.replacement).toLowerCase(Locale.ROOT).contains(text)));
        };
        filter.textProperty().addListener((obs, oldValue, newValue) -> refilter.run());
        onlyTicked.selectedProperty().addListener((obs, oldValue, newValue) -> refilter.run());

        TableView<ShapeRow> table = new TableView<>(filtered);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ShapeRow, Boolean> selectColumn = new TableColumn<>("Deactivate");
        selectColumn.setCellValueFactory(data -> data.getValue().selected);
        selectColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectColumn));
        selectColumn.setEditable(true);
        selectColumn.setPrefWidth(80);
        TableColumn<ShapeRow, String> shapeColumn = textColumn("Shape", 260, row -> shortName(row.shape.iri()));
        TableColumn<ShapeRow, String> nameColumn = textColumn("sh:name", 230, row -> row.shape.name());
        TableColumn<ShapeRow, String> typeColumn = textColumn("Type", 95, row -> typeLabel(row.shape));
        TableColumn<ShapeRow, String> statusColumn = textColumn("Status", 110, row -> row.status);
        TableColumn<ShapeRow, String> replacementColumn = textColumn("Replacing shape", 200, row -> row.replacement);
        TableColumn<ShapeRow, String> reasonColumn = textColumn("Why", 320, row -> row.reasons);
        table.getColumns().addAll(List.of(selectColumn, shapeColumn, nameColumn, typeColumn, statusColumn,
                replacementColumn, reasonColumn));
        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(ShapeRow row, boolean empty) {
                super.updateItem(row, empty);
                setTooltip(empty || row == null ? null : rowTooltip(row));
            }
        });
        rows.addListener((javafx.collections.ListChangeListener<ShapeRow>) change -> updateSummary());

        Button tickSuggested = new Button("Tick suggested only");
        tickSuggested.setOnAction(event -> rows.forEach(row -> row.selected.set(
                row.status.equals("Suggested") || row.status.equals(ADDED_BY_HAND))));
        Button untickAll = new Button("Untick all");
        untickAll.setOnAction(event -> rows.forEach(row -> row.selected.set(false)));
        Button add = new Button("Add constraints...");
        add.setOnAction(event -> openPicker());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox.setHgrow(filter, Priority.ALWAYS);
        HBox tools = new HBox(8, filter, onlyTicked, spacer, tickSuggested, untickAll, add);
        tools.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(6, tools, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private Tooltip rowTooltip(ShapeRow row) {
        StringBuilder text = new StringBuilder(row.shape.iri()).append('\n').append("From: ").append(row.shape.source());
        if (!row.shape.targets().isEmpty()) {
            text.append("\nTargets: ").append(row.shape.targets().stream().map(this::shortName)
                    .collect(Collectors.joining(", ")));
        }
        if (!row.shape.path().isEmpty()) {
            text.append("\nPath: ").append(shortName(row.shape.path()));
        }
        if (!row.shape.description().isEmpty()) {
            text.append("\n\n").append(row.shape.description());
        }
        if (!row.reasons.isEmpty()) {
            text.append("\n\nWhy: ").append(row.reasons);
        }
        Tooltip tooltip = new Tooltip(text.toString());
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(600);
        return tooltip;
    }

    private VBox importSection() {
        Label title = new Label("Configuration");
        title.getStyleClass().add("section-title");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        ColumnConstraints labels = new ColumnConstraints(150);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        tfIri.setPromptText("For example http://example.com/ns/Configuration/Constraints");
        grid.addRow(0, new Label("Configuration IRI:"), tfIri);
        HBox prefixRow = new HBox(8, tfPrefix, new Label("Version:"), tfVersion);
        prefixRow.setAlignment(Pos.CENTER_LEFT);
        tfPrefix.setPrefWidth(90);
        tfVersion.setPrefWidth(90);
        grid.addRow(1, new Label("Prefix:"), prefixRow);
        grid.addRow(2, new Label("Title:"), tfTitle);
        grid.addRow(3, new Label("Description:"), tfDescription);
        cbComments.setSelected(Boolean.parseBoolean(PathMemory.recallValue("tab.deactivation.writeComments", "true")));
        grid.addRow(4, new Label("Comments:"), cbComments);
        cbMerged.setSelected(Boolean.parseBoolean(PathMemory.recallValue("tab.deactivation.writeMerged", "false")));
        cbMerged.setWrapText(true);
        cbMerged.setTooltip(new Tooltip("Reads the local base and replacing files with their own imports, removes the "
                + "ticked shapes, their SPARQL constraints and the sh:property references to them, and writes one "
                + "file that validates like the configuration. Built from the local files, so it works before the "
                + "web URLs in the Imports table can be reached."));
        grid.addRow(5, new Label("Merged file:"), cbMerged);
        tfMergedBase.setPromptText("Optional @base of the merged file, for example http://example.com/ns/Equipment/Constraints");
        tfMergedBase.disableProperty().bind(cbMerged.selectedProperty().not());
        grid.addRow(6, new Label("Merged file @base:"), tfMergedBase);

        Label importsLabel = new Label("Imports - double-click an IRI to edit it, for example to paste the URL the file "
                + "will be published at. The configuration names these; the local files are only read now.");
        importsLabel.setWrapText(true);

        TableView<ImportRow> table = new TableView<>(imports);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(150);
        TableColumn<ImportRow, String> roleColumn = new TableColumn<>("Role");
        roleColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().role));
        roleColumn.setPrefWidth(80);
        TableColumn<ImportRow, String> fileColumn = new TableColumn<>("File");
        fileColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().file.getName()));
        fileColumn.setPrefWidth(250);
        TableColumn<ImportRow, String> iriColumn = new TableColumn<>("Import IRI");
        iriColumn.setCellValueFactory(data -> data.getValue().iri);
        iriColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        iriColumn.setOnEditCommit(event -> {
            event.getRowValue().iri.set(event.getNewValue() == null ? "" : event.getNewValue().trim());
            table.refresh();
        });
        iriColumn.setEditable(true);
        iriColumn.setPrefWidth(450);
        TableColumn<ImportRow, String> statusColumn = new TableColumn<>("Status");
        statusColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
        table.getColumns().addAll(List.of(roleColumn, fileColumn, iriColumn, statusColumn));

        return new VBox(6, title, grid, importsLabel, table);
    }

    private HBox actions() {
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> stage.close());
        Button save = new Button("Save configuration...");
        save.getStyleClass().add("primary-action");
        save.setOnAction(event -> save());
        HBox box = new HBox(8, cancel, save);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    // ---- add by hand --------------------------------------------------------

    /** Lists the base shapes not in the table yet; the ticked ones are added, ticked. */
    private void openPicker() {
        Set<String> listed = rows.stream().map(row -> row.shape.iri()).collect(Collectors.toSet());
        Map<ShapeInfo, BooleanProperty> choices = new LinkedHashMap<>();
        for (ShapeInfo shape : baseShapes) {
            if (!listed.contains(shape.iri())) {
                choices.put(shape, new SimpleBooleanProperty(false));
            }
        }
        if (choices.isEmpty()) {
            GUIhelper.showInfo("Nothing to add", "Every shape of the base files is already in the list.");
            return;
        }

        Stage picker = new Stage();
        picker.setTitle("Add constraints to deactivate");
        picker.initOwner(stage);
        picker.initModality(Modality.WINDOW_MODAL);

        TextField filter = new TextField();
        filter.setPromptText("Filter by IRI, name, path or target class");
        FilteredList<ShapeInfo> filtered = new FilteredList<>(FXCollections.observableArrayList(choices.keySet()));
        filter.textProperty().addListener((obs, oldValue, newValue) -> {
            String text = newValue == null ? "" : newValue.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(shape -> text.isEmpty() || (shape.iri() + " " + shape.name() + " " + shape.path()
                    + " " + String.join(" ", shape.targets())).toLowerCase(Locale.ROOT).contains(text));
        });
        Map<ShapeInfo, String> labels = new HashMap<>();
        choices.keySet().forEach(shape -> labels.put(shape, shortName(shape.iri()) + "  [" + typeLabel(shape) + "]"
                + (shape.name().isEmpty() ? "" : "  " + shape.name())));
        ListView<ShapeInfo> list = new ListView<>(filtered);
        list.setCellFactory(CheckBoxListCell.forListView(choices::get, new javafx.util.StringConverter<>() {
            @Override
            public String toString(ShapeInfo shape) {
                return shape == null ? "" : labels.get(shape);
            }

            @Override
            public ShapeInfo fromString(String text) {
                return null;
            }
        }));
        VBox.setVgrow(list, Priority.ALWAYS);

        Label description = new Label();
        description.setWrapText(true);
        description.setMinHeight(60);
        list.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, shape) -> description.setText(
                shape == null ? "" : shape.iri() + (shape.description().isEmpty() ? "" : "\n" + shape.description())));

        Button tickVisible = new Button("Tick all shown");
        tickVisible.setOnAction(event -> filtered.forEach(shape -> choices.get(shape).set(true)));
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> picker.close());
        Button add = new Button("Add ticked");
        add.getStyleClass().add("primary-action");
        add.setOnAction(event -> {
            choices.forEach((shape, chosen) -> {
                if (chosen.get()) {
                    rows.add(new ShapeRow(shape, true, ADDED_BY_HAND, "", ""));
                }
            });
            picker.close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, tickVisible, spacer, cancel, add);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(8, new Label(choices.size() + " base shape(s) not in the list."), filter,
                new SplitPane(list), description, buttons);
        VBox.setVgrow(root.getChildren().get(2), Priority.ALWAYS);
        root.setPadding(new Insets(12));
        picker.setScene(new Scene(root, 760, 620));
        picker.showAndWait();
    }

    // ---- save ---------------------------------------------------------------

    private void save() {
        List<String> problems = new ArrayList<>();
        String iri = tfIri.getText() == null ? "" : tfIri.getText().trim();
        String iriProblem = ShapeDeactivationConfig.importProblem(iri);
        if (iriProblem != null) {
            problems.add("The configuration IRI " + iriProblem + ".");
        }
        String prefix = tfPrefix.getText() == null ? "" : tfPrefix.getText().trim();
        if (!prefix.matches("[A-Za-z][A-Za-z0-9_\\-]*")) {
            problems.add("The prefix must start with a letter and hold only letters, digits, '_' and '-'.");
        }
        for (ImportRow row : imports) {
            String problem = ShapeDeactivationConfig.importProblem(row.iri.get());
            if (problem != null) {
                problems.add("The import for " + row.file.getName() + " " + problem + ".");
            }
        }
        String mergedBase = tfMergedBase.getText() == null ? "" : tfMergedBase.getText().trim();
        if (cbMerged.isSelected() && !mergedBase.isEmpty()) {
            String problem = ShapeDeactivationConfig.importProblem(mergedBase);
            if (problem != null) {
                problems.add("The @base of the merged file " + problem + ".");
            }
        }
        if (!problems.isEmpty()) {
            GUIhelper.showWarning("Cannot save the configuration", String.join("\n", problems));
            return;
        }
        List<ShapeRow> ticked = rows.stream().filter(row -> row.selected.get()).toList();
        if (ticked.isEmpty() && !confirm("Nothing ticked",
                "No shape is ticked, so the configuration will only import the files. Save it anyway?")) {
            return;
        }
        List<String> skippedImports = imports.stream().filter(row -> row.status().startsWith("Warning"))
                .map(row -> row.file.getName()).toList();
        if (!skippedImports.isEmpty() && !confirm("Imports CimPal would skip",
                "These imports are web URLs without a .ttl or .rdf ending: " + String.join(", ", skippedImports)
                        + ".\n\nCimPal's validation only downloads web imports with an RDF file ending and skips "
                        + "the others, so their constraints would not be applied. Save anyway?")) {
            return;
        }

        File target = eu.griddigit.cimpal.main.util.ModelFactory.fileSaveCustom("Turtle", List.of("*.ttl"),
                "Save the configuration", "Configuration.ttl", "tab.deactivation.configFile");
        if (target == null) {
            return;
        }
        boolean withMerged = cbMerged.isSelected();
        Path mergedTarget = mergedFileFor(target.toPath());
        List<Path> files = imports.stream().map(row -> row.file.toPath()).toList();
        if (withMerged && isInputFile(mergedTarget, files)) {
            GUIhelper.showWarning("Same file twice", mergedTarget.getFileName() + " is one of the files the "
                    + "configuration imports, so the merged file cannot be written there. Save the configuration "
                    + "under another name.");
            return;
        }
        if (withMerged && Files.exists(mergedTarget) && !confirm("Replace the merged file?",
                mergedTarget.getFileName() + " already exists next to the configuration. Replace it?")) {
            return;
        }

        List<ShapeDeactivationConfig.Entry> entries = new ArrayList<>();
        boolean withComments = cbComments.isSelected();
        for (ShapeRow row : ticked) {
            String comment = !withComments ? ""
                    : row.status.equals(ADDED_BY_HAND)
                    ? ADDED_BY_HAND + (row.shape.name().isEmpty() ? "" : ": " + row.shape.name())
                    : (row.shape.name().isEmpty() ? "" : row.shape.name() + " - ") + row.reasons;
            entries.add(new ShapeDeactivationConfig.Entry(row.shape.iri(), row.shape.types(), comment));
        }
        ShapeDeactivationConfig.Options options = new ShapeDeactivationConfig.Options(iri, prefix,
                tfTitle.getText(), tfDescription.getText(), tfVersion.getText(),
                imports.stream().map(row -> row.iri.get().trim()).toList(), entries, prefixes.getNsPrefixMap(),
                null, null);
        try {
            ShapeDeactivationConfig.write(options, target.toPath());
        } catch (IOException | RuntimeException ex) {
            GUIhelper.showUserFriendlyError("Save failed", "The configuration could not be written.", ex);
            return;
        }
        PathMemory.rememberValue("tab.deactivation.configIri", iri);
        PathMemory.rememberValue("tab.deactivation.configPrefix", prefix);
        PathMemory.rememberValue("tab.deactivation.configTitle", tfTitle.getText());
        PathMemory.rememberValue("tab.deactivation.writeComments", Boolean.toString(withComments));
        PathMemory.rememberValue("tab.deactivation.writeMerged", Boolean.toString(withMerged));
        PathMemory.rememberValue("tab.deactivation.mergedBase", mergedBase);
        output.accept("Deactivation configuration saved: " + target + " (" + entries.size()
                + " shape(s) deactivated, " + imports.size() + " import(s)).\n");
        stage.close();
        if (!withMerged) {
            GUIhelper.showInfo("Configuration saved", entries.size() + " shape(s) deactivated, " + imports.size()
                    + " import(s).\n\nSaved to:\n" + target);
            return;
        }

        // The merged file is built from the local files, not from the IRIs the configuration names,
        // which may be web URLs that cannot be reached yet.
        List<String> deactivated = entries.stream().map(ShapeDeactivationConfig.Entry::iri).toList();
        ShapeConfigurationMerger.Header header = ShapeConfigurationMerger.mergedHeader(iri, tfTitle.getText(),
                tfVersion.getText(), mergedBase);
        String saved = entries.size() + " shape(s) deactivated, " + imports.size() + " import(s).\n\nSaved to:\n" + target;
        new Thread(() -> {
            try {
                ShapeConfigurationMerger.Result result = ShapeConfigurationMerger.mergeFiles(files, deactivated, header);
                ShapeConfigurationMerger.write(result, mergedTarget);
                output.accept(describeMerge(result, mergedTarget));
                Platform.runLater(() -> GUIhelper.showInfo("Configuration and merged file saved", saved
                        + "\n\nMerged file:\n" + mergedTarget + mergeWarnings(result)));
            } catch (IOException | RuntimeException ex) {
                Platform.runLater(() -> GUIhelper.showUserFriendlyError("Merge failed", "The configuration was saved to "
                        + target + ", but the merged file could not be written.", ex));
            }
        }, "shacl-configuration-merge").start();
    }

    /**
     * Whether {@code target} is one of {@code inputs} (another spelling, case or link of the same
     * file included). An error checking counts as yes: better refuse than replace an input.
     */
    static boolean isInputFile(Path target, List<Path> inputs) {
        try {
            return ShapeConfigurationMerger.isInput(target, inputs);
        } catch (IOException ex) {
            return true;
        }
    }

    /** {@code Configuration.ttl} becomes {@code Configuration-merged.ttl}, in the same folder. */
    static Path mergedFileFor(Path configuration) {
        String name = configuration.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return configuration.resolveSibling(stem + "-merged.ttl");
    }

    /** A line for the output pane saying what a merge did. */
    static String describeMerge(ShapeConfigurationMerger.Result result, Path target) {
        return "Merged constraint file saved: " + target + " (" + result.documents().size() + " file(s) merged, "
                + result.removedShapes() + " deactivated shape(s) removed, " + result.droppedReferences()
                + " sh:property reference(s) dropped, " + result.keptDeactivated().size()
                + " kept with sh:deactivated true).\n"
                + result.warnings().stream().map(warning -> "  Warning: " + warning + "\n").collect(Collectors.joining());
    }

    /** The merge's warnings, for an information dialog; empty when there are none. */
    static String mergeWarnings(ShapeConfigurationMerger.Result result) {
        return result.warnings().isEmpty() ? ""
                : "\n\nWarnings:\n" + result.warnings().stream().map(warning -> "- " + warning)
                .collect(Collectors.joining("\n"));
    }

    // ---- helpers ------------------------------------------------------------

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        return GuiDialogs.confirm(alert);
    }

    private void updateSummary() {
        long ticked = rows.stream().filter(row -> row.selected.get()).count();
        summary.setText(rows.size() + " shape(s) listed of " + baseShapes.size() + " in the base files; "
                + ticked + " ticked for deactivation.");
    }

    private String shortName(String iri) {
        String shortForm = prefixes.shortForm(iri);
        return shortForm == null ? iri : shortForm;
    }

    private static String typeLabel(ShapeInfo shape) {
        return shape.types().stream()
                .map(type -> type.equals(SH.NodeShape.getURI()) ? "Node" : type.equals(SH.PropertyShape.getURI()) ? "Property" : type)
                .collect(Collectors.joining(", "));
    }

    private static TableColumn<ShapeRow, String> textColumn(String title, double width,
                                                            java.util.function.Function<ShapeRow, String> value) {
        TableColumn<ShapeRow, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        column.setPrefWidth(width);
        column.setEditable(false);
        return column;
    }
}
