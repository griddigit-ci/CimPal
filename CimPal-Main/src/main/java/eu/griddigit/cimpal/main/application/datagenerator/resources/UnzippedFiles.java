/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.datagenerator.resources;

import java.io.InputStream;
import java.util.List;

// For handling multiple files in import.
public class UnzippedFiles {
    private final List<InputStream> inputStreamList;
    private final List<String> fileNames;
    public boolean isSingleZip;

    public UnzippedFiles(List<InputStream> inputStreamList, List<String> fileNames, boolean isSingleZip){
        this.inputStreamList = inputStreamList;
        this.fileNames = fileNames;
        this.isSingleZip = isSingleZip;
    }

    public List<InputStream> getInputStreamList() {return inputStreamList;}
    public List<String> fileNames() {return fileNames;}
}
