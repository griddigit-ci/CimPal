/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.gui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Boundary between CimPal and PowSyBl.
 *
 * <p>Keeping this integration reflective is intentional: PowSyBl publishes a compatible release
 * train, but exposes several optional diagram and load-flow providers through {@link
 * java.util.ServiceLoader}.  CimPal can therefore show a useful installation error instead of
 * failing to start if a downstream distribution deliberately removes one provider.  The Maven
 * dependencies still ship the standard CGMES, OpenLoadFlow, NAD and SLD providers.</p>
 */
public final class PowsyblBridge {
    public enum Diagram { NETWORK_AREA("Network-Area diagram"), SINGLE_LINE("Single-Line diagram");
        private final String title;
        Diagram(String title) { this.title = title; }
        @Override public String toString() { return title; }
    }
    public record IidmElement(String id, String type, String label, List<IidmElement> children) { }
    public record Property(String name, String value) { }

    /** Original CGMES input retained by CimPal; PowSyBl reads it directly, never via Jena XML. */
    public record Profile(String name, Path source) { }

    private Object network;
    private Path materialisedProfiles;
    /** Identity of profiles behind {@link #network}; prevents a full CGMES conversion per view change. */
    private String importedProfilesKey;

    public synchronized boolean available() {
        try {
            // Missing optional PlatformConfig is normal in an embedded desktop application; keep
            // CimPal's Output panel for warnings and failures an operator can act on.
            System.setProperty("org.slf4j.simpleLogger.log.com.powsybl", "warn");
            Class.forName("com.powsybl.iidm.network.Network");
            Class.forName("com.powsybl.loadflow.LoadFlowParameters");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    /** Imports every loaded profile. The RDF browser's filters and selection never affect it. */
    public synchronized void importCgmes(List<Profile> profiles) throws Exception {
        if (profiles.isEmpty()) throw new IllegalArgumentException("Load CGMES profiles before opening Grid view.");
        String profilesKey = profilesKey(profiles);
        if (network != null && profilesKey.equals(importedProfilesKey)) {
            return;
        }
        closeMaterialisedProfiles();
        network = null;
        materialisedProfiles = Files.createTempDirectory("cimpal-cgmes-");
        int index = 0;
        java.util.Set<Path> copied = new java.util.HashSet<>();
        for (Profile profile : profiles) {
            Path source = profile.source();
            if (!Files.isRegularFile(source)) throw new IOException("Original input is no longer available: " + source);
            if (!copied.add(source.toAbsolutePath())) continue;
            String name = source.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
            Files.copy(source, materialisedProfiles.resolve(String.format("%04d-%s", index++, name)));
        }
        Class<?> networkType = Class.forName("com.powsybl.iidm.network.Network");
        network = invokeRead(networkType, materialisedProfiles);
        importedProfilesKey = profilesKey;
    }

    public synchronized boolean isPreparedFor(List<Profile> profiles) {
        return network != null && profilesKey(profiles).equals(importedProfilesKey);
    }

    private static String profilesKey(List<Profile> profiles) {
        return profiles.stream().map(profile -> profile.name() + ':' + profile.source().toAbsolutePath()
                + ':' + profile.source().toFile().lastModified())
                .collect(java.util.stream.Collectors.joining("|"));
    }

    public synchronized String draw(Diagram diagram, String selectedEquipmentId) throws Exception {
        ensureNetwork();
        Path svg = Files.createTempFile("cimpal-powsybl-", ".svg");
        try {
            String type = diagram == Diagram.NETWORK_AREA
                    ? "com.powsybl.nad.NetworkAreaDiagram" : "com.powsybl.sld.SingleLineDiagram";
            invokeDraw(Class.forName(type), svg, selectedEquipmentId);
            return Files.readString(svg);
        } finally {
            Files.deleteIfExists(svg);
        }
    }

    /** Releases IIDM, its CGMES/RDF4J backing data and staged source copies. */
    public synchronized void release() throws IOException {
        network = null;
        importedProfilesKey = null;
        closeMaterialisedProfiles();
    }

    /** Native IIDM hierarchy for the CimPal PowsyBl browser. */
    public synchronized IidmElement iidmTree() throws Exception {
        ensureNetwork();
        List<IidmElement> substations = elements(network, "com.powsybl.iidm.network.Network", "getSubstationStream", "Substation", true);
        return new IidmElement("", "Network", "IIDM network", substations);
    }

    /** Read simple public IIDM attributes for the selected element without exposing implementation classes. */
    public synchronized List<Property> iidmProperties(String id) throws Exception {
        ensureNetwork();
        Class<?> networkType = Class.forName("com.powsybl.iidm.network.Network");
        Object element = invoke(networkType.getMethod("getIdentifiable", String.class), network, id);
        if (element == null) return List.of(new Property("Identifier", id), new Property("Status", "not found"));
        Map<String, String> values = new java.util.TreeMap<>();
        values.put("Identifier", id);
        values.put("IIDM type", element.getClass().getInterfaces().length == 0 ? element.getClass().getSimpleName()
                : element.getClass().getInterfaces()[0].getSimpleName());
        for (Class<?> api : element.getClass().getInterfaces()) {
            for (Method method : api.getMethods()) {
                if (method.getParameterCount() != 0 || !(method.getName().startsWith("get") || method.getName().startsWith("is"))) continue;
                Class<?> result = method.getReturnType();
                if (!(result.isPrimitive() || result == String.class || result.isEnum() || result == java.util.Optional.class)) continue;
                try {
                    Object value = invoke(method, element);
                    values.put(method.getName().replaceFirst("^(get|is)", ""), Objects.toString(value, ""));
                } catch (Exception ignored) { }
            }
        }
        return values.entrySet().stream().map(entry -> new Property(entry.getKey(), entry.getValue())).toList();
    }

    private List<IidmElement> elements(Object owner, String interfaceName, String streamMethod, String type, boolean withVoltageLevels) throws Exception {
        Method method = Class.forName(interfaceName).getMethod(streamMethod);
        Object result = invoke(method, owner);
        if (!(result instanceof java.util.stream.Stream<?> stream)) return List.of();
        try (stream) {
            return stream.map(element -> {
                try {
                    String id = identifier(element);
                    List<IidmElement> children = withVoltageLevels
                            ? voltageLevels(element) : connectables(element);
                    String actualType = type.equals("Equipment") ? iidmType(element) : type;
                    return new IidmElement(id, actualType, actualType + ": " + id, children);
                } catch (Exception e) { return new IidmElement("", type, type + " (unavailable)", List.of()); }
            }).toList();
        }
    }

    private List<IidmElement> voltageLevels(Object substation) throws Exception {
        return elements(substation, "com.powsybl.iidm.network.Substation", "getVoltageLevelStream", "Voltage level", false);
    }

    private List<IidmElement> connectables(Object voltageLevel) {
        try {
            return elements(voltageLevel, "com.powsybl.iidm.network.VoltageLevel", "getConnectableStream", "Equipment", false);
        } catch (Exception ignored) { return List.of(); }
    }

    private static String identifier(Object element) throws Exception {
        Class<?> identifiable = Class.forName("com.powsybl.iidm.network.Identifiable");
        return Objects.toString(invoke(identifiable.getMethod("getId"), element));
    }

    private static String iidmType(Object element) {
        for (Class<?> api : element.getClass().getInterfaces()) {
            String simple = api.getSimpleName();
            if (!simple.equals("Identifiable") && !simple.equals("Connectable")) return simple;
        }
        return "Equipment";
    }

    /** Shows all public JavaBean-like LoadFlowParameters exposed by the installed PowSyBl API. */
    public synchronized Object configurePowerFlow() throws Exception {
        ensureNetwork();
        Class<?> parametersType = Class.forName("com.powsybl.loadflow.LoadFlowParameters");
        Object parameters = parametersType.getConstructor().newInstance();
        Map<Method, Object> editors = editors(parametersType, parameters);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("PowsyBl power-flow settings");
        dialog.setHeaderText("Settings exposed by the installed PowsyBl LoadFlowParameters API");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        GridPane pane = new GridPane(); pane.setHgap(10); pane.setVgap(8);
        int row = 0;
        for (Map.Entry<Method, Object> entry : editors.entrySet()) {
            pane.add(new Label(label(entry.getKey())), 0, row);
            pane.add((javafx.scene.Node) entry.getValue(), 1, row++);
        }
        VBox box = new VBox(pane); box.setPrefWidth(680); VBox.setVgrow(pane, Priority.ALWAYS);
        dialog.getDialogPane().setContent(box);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return null;
        for (Map.Entry<Method, Object> entry : editors.entrySet()) set(parameters, entry.getKey(), entry.getValue());
        return parameters;
    }

    /** Run away from the FX thread so the settings dialog never makes the UI appear stuck. */
    public synchronized String runPowerFlow(Object parameters) throws Exception {
        return Objects.toString(invokeLoadFlow(parameters));
    }

    private static Object invokeRead(Class<?> networkType, Path directory) throws Exception {
        for (Method method : networkType.getMethods()) {
            if (!method.getName().equals("read") || !Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 1 && types[0] == Path.class) return invoke(method, null, directory);
            if (types.length == 1 && types[0] == String.class) return invoke(method, null, directory.toString());
        }
        throw new IllegalStateException("This PowSyBl version does not expose Network.read(Path).");
    }

    private void invokeDraw(Class<?> type, Path svg, String equipmentId) throws Exception {
        Class<?> networkType = Class.forName("com.powsybl.iidm.network.Network");
        if (type.getName().equals("com.powsybl.nad.NetworkAreaDiagram")) {
            // The basic Network/Path overload draws the complete imported network.
            invoke(type.getMethod("draw", networkType, Path.class), null, network, svg);
            return;
        }
        // An SLD needs a concrete substation/voltage-level. Pick one only when selection did
        // not resolve to an IIDM element, then call the documented Network/String/Path overload.
        invoke(type.getMethod("draw", networkType, String.class, Path.class), null,
                network, usableDiagramId(equipmentId), svg);
    }

    private String usableDiagramId(String selectedId) throws Exception {
        if (selectedId != null && !selectedId.isBlank()) {
            String local = selectedId.substring(Math.max(selectedId.lastIndexOf('#'), selectedId.lastIndexOf('/')) + 1);
            for (String lookup : List.of(selectedId, local)) {
                for (String methodName : List.of("getSubstation", "getVoltageLevel")) {
                    Object element = network.getClass().getMethod(methodName, String.class).invoke(network, lookup);
                    if (element != null) return lookup;
                }
            }
        }
        Object stream = network.getClass().getMethod("getSubstationStream").invoke(network);
        try (var substations = (java.util.stream.Stream<?>) stream) {
            Object first = substations.findFirst().orElse(null);
            if (first == null) throw new IllegalStateException("The imported network has no substation to draw.");
            // The concrete IIDM implementation is not exported from its module. Invoke the
            // public interface, otherwise reflection fails when CimPal runs as a named module.
            Class<?> identifiable = Class.forName("com.powsybl.iidm.network.Identifiable");
            return Objects.toString(invoke(identifiable.getMethod("getId"), first));
        }
    }

    /** Preserve PowsyBl's real diagnostic rather than leaking InvocationTargetException to UI. */
    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("PowsyBl invocation failed", cause);
        }
    }

    private Object invokeLoadFlow(Object parameters) throws Exception {
        Class<?> loadFlow = Class.forName("com.powsybl.loadflow.LoadFlow");
        for (Method method : loadFlow.getMethods()) {
            if (!method.getName().equals("run") || !Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0].isInstance(network) && types[1].isInstance(parameters))
                return invoke(method, null, network, parameters);
        }
        throw new IllegalStateException("No compatible LoadFlow.run(Network, LoadFlowParameters) API was found.");
    }

    private static Map<Method, Object> editors(Class<?> type, Object instance) throws Exception {
        Map<Method, Object> result = new LinkedHashMap<>();
        List<Method> getters = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0 && (method.getName().startsWith("get") || method.getName().startsWith("is"))
                    && supported(method.getReturnType()) && setter(type, method) != null) getters.add(method);
        }
        getters.sort(Comparator.comparing(Method::getName));
        for (Method getter : getters) {
            Object value = getter.invoke(instance); Class<?> valueType = getter.getReturnType();
            if (valueType == boolean.class || valueType == Boolean.class) {
                CheckBox box = new CheckBox(); box.setSelected(Boolean.TRUE.equals(value)); result.put(getter, box);
            } else if (valueType.isEnum()) {
                ComboBox<Object> box = new ComboBox<>(); box.getItems().setAll(valueType.getEnumConstants()); box.setValue(value); result.put(getter, box);
            } else { TextField field = new TextField(Objects.toString(value, "")); field.setPrefWidth(300); result.put(getter, field); }
        }
        return result;
    }

    private static boolean supported(Class<?> type) {
        return type == boolean.class || type == Boolean.class || type == int.class || type == Integer.class
                || type == double.class || type == Double.class || type == String.class || type.isEnum();
    }
    private static Method setter(Class<?> type, Method getter) {
        String stem = getter.getName().startsWith("is") ? getter.getName().substring(2) : getter.getName().substring(3);
        try { return type.getMethod("set" + stem, getter.getReturnType()); } catch (NoSuchMethodException ignored) { return null; }
    }
    private static String label(Method getter) { return getter.getName().replaceFirst("^(get|is)", "").replaceAll("([A-Z])", " $1").trim(); }
    private static void set(Object parameters, Method getter, Object editor) throws Exception {
        Method setter = setter(parameters.getClass(), getter); Class<?> type = getter.getReturnType(); Object value;
        if (editor instanceof CheckBox box) value = box.isSelected();
        else if (editor instanceof ComboBox<?> box) value = box.getValue();
        else { String text = ((TextField) editor).getText(); value = type == int.class || type == Integer.class ? Integer.valueOf(text) : type == double.class || type == Double.class ? Double.valueOf(text) : text; }
        setter.invoke(parameters, value);
    }
    private void ensureNetwork() { if (network == null) throw new IllegalStateException("The complete CGMES network has not been imported yet."); }
    private void closeMaterialisedProfiles() throws IOException {
        if (materialisedProfiles == null) return;
        try (var paths = Files.walk(materialisedProfiles)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); }
        materialisedProfiles = null;
    }
}
