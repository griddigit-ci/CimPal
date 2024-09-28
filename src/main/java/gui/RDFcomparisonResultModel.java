/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package gui;

import javafx.beans.property.SimpleStringProperty;

public class RDFcomparisonResultModel {
    private final SimpleStringProperty item;
    private final SimpleStringProperty rdfType;
    private final SimpleStringProperty property;
    private final SimpleStringProperty valueModelA;
    private final SimpleStringProperty valueModelB;


    public RDFcomparisonResultModel(String iitem, String irdfType, String iproperty, String ivalueModelA, String ivalueModelB) {
        this.item = new SimpleStringProperty(iitem);
        this.rdfType = new SimpleStringProperty(irdfType);
        this.property = new SimpleStringProperty(iproperty);
        this.valueModelA = new SimpleStringProperty(ivalueModelA);
        this.valueModelB = new SimpleStringProperty(ivalueModelB);
    }

    public String getItem() {
        return item.get();
    }
    public void setItem(String iitem) {
        item.set(iitem);
    }

    public String getRdfType() {
        return rdfType.get();
    }
    public void setRdfType(String irdfType) {
        rdfType.set(irdfType);
    }

    public String getProperty() {
        return property.get();
    }
    public void setProperty(String iproperty) {
        property.set(iproperty);
    }

    public String getValueModelA() {
        return valueModelA.get();
    }
    public void setValueModelA(String ivalueModelA) {
        valueModelA.set(ivalueModelA);
    }

    public String getValueModelB() {
        return valueModelB.get();
    }
    public void setValueModelB(String ivalueModelB) {
        valueModelB.set(ivalueModelB);
    }
}
