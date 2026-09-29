/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.model;

public class RDFAttributeData {
    private String prefix;
    private String name;
    private String value;
    private String tpe;
    private String cardinality;

    public RDFAttributeData(String prefix, String name,String value, String tpe) {
        this.prefix = prefix;
        this.name = name;
        this.value = value;
        this.tpe = tpe;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getName() {
        return name;
    }

    public String getFullName() {
        return prefix + ":" + name;
    }

    public String getValue() {
        return value;
    }

    public String getTpe() {
        return tpe;
    }

    public String getCardinality() {
        return cardinality;
    }
}
