package eu.griddigit.cimpal.main.gui;

import eu.griddigit.cimpal.main.application.MainController;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.scene.control.TreeItem;

public class TaggedTreeItem<String> extends TreeItem<String> {
    private StringProperty tag;
    private boolean visible = true; // Initial visibility is true

    public TaggedTreeItem(String value) {
        super(value);
        tag = new SimpleStringProperty();

        this.expandedProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue) {
                MainController.treeInstanceDataExpand((TaggedTreeItem<java.lang.String>) this);
            }
        });
    }

    public StringProperty tagProperty() {
        return tag;
    }

    public String getTag() {
        return (String) tag.get();
    }

    public void setTag(String tag) {
        this.tag.set((java.lang.String) tag);
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        setExpanded(visible); // Collapse the item if it's hidden
        Node graphic = getGraphic();
        if (graphic != null) {
            graphic.setVisible(visible); // Set the visibility of the graphic
        }
    }
}
