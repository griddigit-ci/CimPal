/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.gui;

import javafx.collections.ObservableList;

public class TableColumnsSetup {

    private String column1;
    private String column2;

    private ObservableList<String> column1List;


    public TableColumnsSetup(String column1, String column2, ObservableList<String> column1List)
    {
        this.column1 = column1;
        this.column2 = column2;
        this.column1List = column1List;
    }


    public String getColumn1()
    {
        return column1;
    }


    public void setColumn1( String column1 )
    {
        this.column1 = column1;
    }


    public String getColumn2()
    {
        return column2;
    }


    public void setColumn2( String column2 )
    {
        this.column2 = column2;
    }


    public ObservableList<String> getColumn1List()
    {
        return column1List;
    }


    public void setColumn1List( ObservableList<String> column1List )
    {
        this.column1List = column1List;
    }

}
