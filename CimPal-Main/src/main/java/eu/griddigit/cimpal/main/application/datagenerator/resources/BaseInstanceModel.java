/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.datagenerator.resources;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;

import java.util.Set;
import java.util.StringJoiner;

public class BaseInstanceModel {
    // indexes 0=datetime; 1=process; 2=TSO; 3=Profile; 4=version
    private Model baseInstanceModel;
    private String fileName;
    private String datetime;
    private String process;
    private String tso;
    private String profile;
    private String version;
    // The objects the file wrote with rdf:about, and with rdf:ID; null when not known (a file that
    // was not RDF/XML).
    private Set<Resource> aboutSubjects;
    private Set<Resource> idSubjects;

    /**
     * A model known by its file name.
     * <p>
     * A name following the CGMES convention {@code <datetime>_<process>_<TSO>_<profile>_<version>}
     * fills in those parts. Any other name is kept as it is and leaves the parts null: Network Code
     * Profile datasets are named freely ({@code Belgovia_CO.xml}), and splitting such a name used to
     * throw before the file was even read. For those, the profile comes from the file's content -
     * see {@link #setProfile(String)}.
     */
    public BaseInstanceModel(String fileName) {
        this.fileName = fileName;
        String[] originalNameInParts = fileName.split("_",5);
        if (originalNameInParts.length == 5) {
            datetime = originalNameInParts[0];
            process = originalNameInParts[1];
            tso = originalNameInParts[2];
            profile = originalNameInParts[3];
            version = originalNameInParts[4];
        }
    }

    public BaseInstanceModel(String datetime, String process, String tso, String profile, String version) {
        this.datetime = datetime;
        this.process = process;
        this.tso = tso;
        this.profile = profile;
        this.version = version;
        StringJoiner joiner = new StringJoiner("_");
        joiner.add(datetime).add(process).add(tso).add(profile).add(version);
        this.fileName = joiner.toString();
    }

    /**
     * A copy of {@code source}'s metadata under a new file name and model. Used where a task
     * produces a new model for an existing file, so a profile that was read from the content is
     * not lost by re-deriving it from the name.
     */
    public BaseInstanceModel(BaseInstanceModel source, String fileName, Model model) {
        this.fileName = fileName;
        this.datetime = source.datetime;
        this.process = source.process;
        this.tso = source.tso;
        this.profile = source.profile;
        this.version = source.version;
        this.aboutSubjects = source.aboutSubjects;
        this.idSubjects = source.idSubjects;
        this.baseInstanceModel = model;
    }

    /** Whether the file name follows the CGMES convention, so the name parts are all known. */
    public boolean hasCgmesFileName() {
        return datetime != null;
    }

    public Model getBaseInstanceModel() {
        return baseInstanceModel;
    }

    public void setBaseInstanceModel(Model baseInstanceModel) {
        this.baseInstanceModel = baseInstanceModel;
    }

    public String getDatetime() {
        return datetime;
    }

    public String getProcess() {
        return process;
    }

    public String getTso() {
        return tso;
    }

    /**
     * The profile keyword of the model - EQ, SSH, CO, RA... It is what the loaded RDFS and the
     * serialisation rules are keyed by. Null when it could be read neither from the file name nor
     * from the content.
     */
    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getVersion() {
        return version;
    }

    /**
     * The objects this file wrote with rdf:about - the ones it only refers to, plus any it
     * identifies by URN - or null when that is not known. See {@code RdfAboutScanner}. A task that
     * changes ids has to change them here as well, or the objects are written back in the form the
     * serialisation rules give their class rather than the one they came in.
     */
    public Set<Resource> getAboutSubjects() {
        return aboutSubjects;
    }

    /** The objects this file wrote with rdf:ID, the ones it defines; null when not known. */
    public Set<Resource> getIdSubjects() {
        return idSubjects;
    }

    /** Records the form the file was written in; both null when it is not known. */
    public void setWrittenForm(Set<Resource> aboutSubjects, Set<Resource> idSubjects) {
        this.aboutSubjects = aboutSubjects;
        this.idSubjects = idSubjects;
    }

    public String getFileName() {
        return fileName;
    }
}
