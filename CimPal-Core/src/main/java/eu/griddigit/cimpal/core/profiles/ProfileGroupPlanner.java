/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.utils.LogSanitizer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides which instance documents are validated together and with which shapes.
 *
 * <p>By folder: each first-level subfolder of the models folder is an area, and the documents
 * directly in the models folder are one more. Within an area the documents that declare
 * {@code md:Model.profile} and those that declare {@code dcterms:conformsTo} form separate groups
 * ({@code cgmes-<area>}, {@code nc-<area>}), since they are constrained by different profile
 * families; the second takes the first as context. Documents under a boundary folder form the
 * boundary groups and are context for every other group.
 *
 * <p>Explicit groups replace all of that: each names its reported and context documents.
 *
 * <p>The shapes of a document are those of its declared profiles. Shapes that only apply to
 * boundary data are left out everywhere but in a boundary group, and shapes no descriptor refers
 * to can be added to every group that reports a CGMES document.
 */
public final class ProfileGroupPlanner {

    /** A document that is not validated, and why. Both are safe to log. */
    public record Skipped(String source, String reason) {
        public Skipped {
            // Names of archive entries and profile IRIs are text of the data.
            source = LogSanitizer.forLog(source);
            reason = LogSanitizer.forLog(reason);
        }
    }

    /**
     * An explicit group.
     *
     * @param reported       files or folders; a folder stands for every document under it
     * @param context        files or folders loaded with them, never reported here
     * @param boundary       true to keep the boundary-only shapes
     * @param extraShapes    shape files added to the group's shapes
     * @param assumeProfiles profile IRIs for reported documents that declare none
     */
    public record GroupSpec(String name, List<Path> reported, List<Path> context, boolean boundary,
                            List<Path> extraShapes, List<String> assumeProfiles) {
        public GroupSpec {
            if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 ._()-]{0,79}")
                    || eu.griddigit.cimpal.core.utils.PathPolicy.isReservedName(name)) {
                throw new IllegalArgumentException("A group name is 1 to 80 letters, digits, spaces or . _ ( ) -,"
                        + " starting with a letter or digit, and not a device name");
            }
            reported = List.copyOf(reported);
            context = context == null ? List.of() : List.copyOf(context);
            extraShapes = extraShapes == null ? List.of() : List.copyOf(extraShapes);
            assumeProfiles = assumeProfiles == null ? List.of() : List.copyOf(assumeProfiles);
        }
    }

    /**
     * How to plan.
     *
     * @param modelsDir             the folder the documents were found under
     * @param boundaryFolders       folders of boundary data, relative to {@code modelsDir}
     * @param boundaryShapesPattern shape files whose name contains this apply to boundary groups
     *                              only; empty to apply every shape everywhere
     * @param commonShapes          shape files added to every group that reports a CGMES document
     * @param allowUnmapped         true to skip, rather than refuse, a document whose profile is
     *                              unknown or lacks shapes
     * @param groups                explicit groups; empty to group by folder
     */
    public record Settings(Path modelsDir, List<String> boundaryFolders, String boundaryShapesPattern,
                           List<Path> commonShapes, boolean allowUnmapped,
                           List<GroupSpec> groups) {
        public Settings {
            boundaryFolders = List.copyOf(boundaryFolders);
            commonShapes = List.copyOf(commonShapes);
            groups = List.copyOf(groups);
            boundaryShapesPattern = boundaryShapesPattern == null ? "" : boundaryShapesPattern;
        }
    }

    /**
     * The outcome of planning.
     *
     * @param complete false when a document was skipped because its profile is unknown or lacks
     *                 shapes: the run then says nothing about that document
     */
    public record Plan(List<ProfileGroup> groups, List<Skipped> skipped, boolean complete) {
        public Plan {
            groups = List.copyOf(groups);
            skipped = List.copyOf(skipped);
        }
    }

    /** A document with the profiles it is validated against. */
    private record Member(InstanceHeader header, InstanceHeader.Kind kind, List<Path> shapes) {
    }

    private final Settings settings;
    private final ProfileCatalog catalog;
    private final List<Skipped> skipped = new ArrayList<>();
    private boolean complete = true;

    private ProfileGroupPlanner(Settings settings, ProfileCatalog catalog) {
        this.settings = settings;
        this.catalog = catalog;
    }

    /**
     * Plans the groups for {@code headers}.
     *
     * @throws IllegalArgumentException if a document declares a profile that is unknown or lacks
     *                                  shapes and that is not allowed, or the explicit groups are
     *                                  inconsistent
     */
    public static Plan plan(List<InstanceHeader> headers, ProfileCatalog catalog, Settings settings) {
        ProfileGroupPlanner planner = new ProfileGroupPlanner(settings, catalog);
        List<ProfileGroup> groups = settings.groups().isEmpty()
                ? planner.byFolder(headers)
                : planner.explicit(headers);
        return new Plan(groups, planner.skipped, planner.complete);
    }

    // ---- by folder -----------------------------------------------------------------------

    private List<ProfileGroup> byFolder(List<InstanceHeader> headers) {
        Path root = settings.modelsDir().toAbsolutePath().normalize();
        List<Path> boundaryRoots = settings.boundaryFolders().stream()
                .map(f -> root.resolve(f).normalize()).toList();

        // area name -> kind -> members, in name order; the boundary is kept apart.
        Map<String, Map<InstanceHeader.Kind, List<Member>>> areas = new TreeMap<>();
        Map<InstanceHeader.Kind, List<Member>> boundary = new TreeMap<>();
        for (InstanceHeader header : headers) {
            Member member = member(header, List.of());
            if (member == null) {
                continue;
            }
            Path file = header.file().toAbsolutePath().normalize();
            if (boundaryRoots.stream().anyMatch(file::startsWith)) {
                boundary.computeIfAbsent(member.kind(), k -> new ArrayList<>()).add(member);
                continue;
            }
            Path relative = file.startsWith(root) ? root.relativize(file) : file.getFileName();
            String area = relative.getNameCount() > 1 ? relative.getName(0).toString() : "(root)";
            areas.computeIfAbsent(area, a -> new TreeMap<>())
                    .computeIfAbsent(member.kind(), k -> new ArrayList<>()).add(member);
        }

        List<Member> boundaryCgmes = boundary.getOrDefault(InstanceHeader.Kind.CGMES, List.of());
        List<ProfileGroup> groups = new ArrayList<>();
        addGroup(groups, "cgmes-boundary", true, boundaryCgmes, List.of(), List.of());
        addGroup(groups, "nc-boundary", true, boundary.getOrDefault(InstanceHeader.Kind.NC, List.of()),
                boundaryCgmes, List.of());
        areas.forEach((area, byKind) -> {
            List<Member> cgmes = byKind.getOrDefault(InstanceHeader.Kind.CGMES, List.of());
            addGroup(groups, "cgmes-" + area, false, cgmes, boundaryCgmes, List.of());
            List<Member> ncContext = new ArrayList<>(cgmes);
            ncContext.addAll(boundaryCgmes);
            addGroup(groups, "nc-" + area, false, byKind.getOrDefault(InstanceHeader.Kind.NC, List.of()),
                    ncContext, List.of());
        });
        return groups;
    }

    // ---- explicit groups -----------------------------------------------------------------

    private List<ProfileGroup> explicit(List<InstanceHeader> headers) {
        List<ProfileGroup> groups = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Map<String, String> reportedIn = new LinkedHashMap<>();
        Map<String, Member> members = new LinkedHashMap<>();
        for (GroupSpec spec : settings.groups()) {
            if (!names.add(spec.name())) {
                throw new IllegalArgumentException("Two groups are named " + LogSanitizer.forLog(spec.name()));
            }
            List<Member> reported = new ArrayList<>();
            for (InstanceHeader header : under(headers, spec.reported())) {
                String other = reportedIn.putIfAbsent(header.id(), spec.name());
                if (other != null) {
                    throw new IllegalArgumentException(LogSanitizer.forLog(header.source()) + " is reported in two groups: "
                            + LogSanitizer.forLog(other) + " and " + LogSanitizer.forLog(spec.name()));
                }
                Member member = member(header, spec.assumeProfiles());
                if (member != null) {
                    members.put(header.id(), member);
                    reported.add(member);
                }
            }
            if (reported.isEmpty()) {
                throw new IllegalArgumentException("Group " + LogSanitizer.forLog(spec.name())
                        + " reports no document that can be validated");
            }
            List<Member> context = new ArrayList<>();
            for (InstanceHeader header : under(headers, spec.context())) {
                if (reported.stream().noneMatch(m -> m.header().id().equals(header.id()))) {
                    // A context document needs no shapes: it only supplies what others refer to.
                    context.add(new Member(header, header.kind(), List.of()));
                }
            }
            addGroup(groups, spec.name(), spec.boundary(), reported, context, spec.extraShapes());
        }
        for (InstanceHeader header : headers) {
            if (!reportedIn.containsKey(header.id())) {
                skipped.add(new Skipped(header.source(), "not reported in any group"));
            }
        }
        return groups;
    }

    private static List<InstanceHeader> under(List<InstanceHeader> headers, List<Path> filesOrFolders) {
        // Compared as paths only, never looked up: a group may not probe what lies outside the
        // documents that were found. A path that equals a document is that document; any other
        // stands for the documents under it.
        List<Path> wanted = filesOrFolders.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        return headers.stream()
                .filter(h -> {
                    Path file = h.file().toAbsolutePath().normalize();
                    return wanted.stream().anyMatch(file::startsWith);
                })
                .toList();
    }

    // ---- shared --------------------------------------------------------------------------

    /** The document with its shapes, or null when it is skipped (and recorded as such). */
    private Member member(InstanceHeader header, List<String> assumeProfiles) {
        List<String> profiles = header.profiles();
        InstanceHeader.Kind kind = header.kind();
        if (kind == InstanceHeader.Kind.NONE) {
            if (assumeProfiles.isEmpty()) {
                skipped.add(new Skipped(header.source(), "no application profile declared"));
                return null;
            }
            profiles = assumeProfiles;
            kind = InstanceHeader.Kind.CGMES;
        }
        ProfileCatalog.Resolution resolution = catalog.resolve(profiles);
        if (!resolution.complete()) {
            String reason = (resolution.unmapped().isEmpty() ? ""
                    : "unmapped profile: " + String.join(", ", resolution.unmapped()))
                    + (resolution.unmapped().isEmpty() || resolution.incomplete().isEmpty() ? "" : "; ")
                    + (resolution.incomplete().isEmpty() ? ""
                    : "profile without all its shapes: " + String.join(", ", resolution.incomplete()));
            if (!settings.allowUnmapped()) {
                throw new IllegalArgumentException(LogSanitizer.forLog(header.source()) + ": "
                        + LogSanitizer.forLog(reason) + " (see the gaps of the profile library; allow unmapped"
                        + " profiles to skip such documents)");
            }
            skipped.add(new Skipped(header.source(), reason));
            complete = false;
            return null;
        }
        return new Member(header, kind, resolution.shapeFiles());
    }

    private void addGroup(List<ProfileGroup> groups, String name, boolean boundary, List<Member> reported,
                          List<Member> context, List<Path> extraShapes) {
        if (reported.isEmpty()) {
            return;
        }
        // Shapes of the group as a whole: they also apply to each of its documents on its own.
        Set<Path> shared = new TreeSet<>(extraShapes);
        if (reported.stream().anyMatch(m -> m.kind() == InstanceHeader.Kind.CGMES)) {
            shared.addAll(settings.commonShapes());
        }
        Set<Path> union = new TreeSet<>(shared);
        Map<String, List<Path>> dataset = new LinkedHashMap<>();
        for (Member member : reported) {
            List<Path> shapes = member.shapes().stream()
                    .filter(p -> boundary || settings.boundaryShapesPattern().isEmpty()
                            || !p.getFileName().toString().contains(settings.boundaryShapesPattern()))
                    .toList();
            if (shapes.isEmpty()) {
                // The boundary pattern took every shape of the profile: nothing would check the document.
                throw new IllegalArgumentException(LogSanitizer.forLog(member.header().source())
                        + ": no shapes are left for it in group " + LogSanitizer.forLog(name)
                        + " once the boundary-only shapes are taken out");
            }
            union.addAll(shapes);
            Set<Path> own = new TreeSet<>(shapes);
            own.addAll(shared);
            dataset.put(member.header().id(), new ArrayList<>(own));
        }
        groups.add(new ProfileGroup(name, boundary,
                reported.stream().map(Member::header).toList(),
                context.stream().map(Member::header).toList(),
                new ArrayList<>(union), dataset));
    }
}
