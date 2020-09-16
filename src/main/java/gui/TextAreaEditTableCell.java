/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package gui;


import javafx.scene.text.Text;
import javafx.util.converter.DefaultStringConverter;

//public class TextFieldEditTableCell<S> extends TextFieldTableCell<S, String> { //this is used if TextField is used instead of TextArea
public class TextAreaEditTableCell<S> extends TextAreaTableCell<S, String> {

    private final Text cellText;

    public TextAreaEditTableCell() {
        super(new DefaultStringConverter());
        this.cellText = createText();
    }

    @Override
    public void cancelEdit() {
        super.cancelEdit();
        setGraphic(cellText);
    }

    @Override
    public void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (!isEmpty() && !isEditing()) {
            setGraphic(cellText);
        }
    }

    private Text createText() {
        Text text = new Text();
        text.wrappingWidthProperty().bind(widthProperty());
        text.textProperty().bind(itemProperty());
        return text;
    }
}

