/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.gui;


import javafx.beans.binding.Bindings;
import javafx.scene.control.TableColumn;
import javafx.scene.text.Text;
import javafx.util.converter.DefaultStringConverter;

//public class TextFieldEditTableCell<S> extends TextFieldTableCell<S, String> { //this is used if TextField is used instead of TextArea
public class TextAreaEditTableCell<S> extends TextAreaTableCell<S, String> {

    private final Text cellText;

    public TextAreaEditTableCell() {
        super(new DefaultStringConverter());
        this.cellText = createText();
        // Wrap at the column width, not the cell's own width: the cell width is only known after
        // layout, so a cell measured before layout reported a different height than the drawn rows.
        tableColumnProperty().addListener((obs, oldColumn, newColumn) -> bindWrappingWidth(newColumn));
    }

    @Override
    public void cancelEdit() {
        super.cancelEdit();
        showText();
    }

    @Override
    public void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (!isEmpty() && !isEditing()) {
            showText();
        }
    }

    // the Text graphic carries the value; the cell's own label text would only compete for space
    private void showText() {
        setText(null);
        setGraphic(cellText);
    }

    private void bindWrappingWidth(TableColumn<S, ?> column) {
        if (column == null) {
            cellText.wrappingWidthProperty().unbind();
            return;
        }
        cellText.wrappingWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(0, column.getWidth() - snappedLeftInset() - snappedRightInset()),
                column.widthProperty(), insetsProperty()));
    }

    private Text createText() {
        Text text = new Text();
        // Text nodes use -fx-fill rather than the table cell's -fx-text-fill.
        // Give the shared stylesheet a precise hook so wrapped table values
        // remain readable in every application theme.
        text.getStyleClass().add("cimpal-table-cell-text");
        text.textProperty().bind(itemProperty());
        return text;
    }
}
