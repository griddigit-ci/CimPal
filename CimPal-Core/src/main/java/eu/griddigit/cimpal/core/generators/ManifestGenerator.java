package eu.griddigit.cimpal.core.generators;

import eu.griddigit.cimpal.core.utils.ModelFactory;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class ManifestGenerator {

    private static final String NS_ADMS = "http://www.w3.org/ns/adms#";
    private static final String NS_CIM = "https://cim.ucaiug.io/ns#";
    private static final String NS_DCAT = "http://www.w3.org/ns/dcat#";
    private static final String NS_DATACIM = "https://cim4.eu/ns/datacim#";
    private static final String NS_DCTERMS = "http://purl.org/dc/terms/";
    private static final String NS_EU = "https://cim.ucaiug.io/ns/eu#";
    private static final String NS_NC = "https://cim4.eu/ns/nc#";
    private static final String NS_PROV = "http://www.w3.org/ns/prov#";
    private static final String NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String NS_MD = "http://iec.ch/TC57/61970-552/ModelDescription/1#";

    private static final Resource MD_FULL_MODEL = ResourceFactory.createResource(NS_MD + "FullModel");
    private static final Resource DCAT_CATALOG = ResourceFactory.createResource(NS_DCAT + "Catalog");
    private static final Resource DCAT_DATASET = ResourceFactory.createResource(NS_DCAT + "Dataset");
    private static final Resource DCAT_DISTRIBUTION = ResourceFactory.createResource(NS_DCAT + "Distribution");

    private static final Property DCTERMS_ACCESS_RIGHTS = ResourceFactory.createProperty(NS_DCTERMS, "accessRights");
    private static final Property DCTERMS_CONFORMS_TO = ResourceFactory.createProperty(NS_DCTERMS, "conformsTo");
    private static final Property DCTERMS_DESCRIPTION = ResourceFactory.createProperty(NS_DCTERMS, "description");
    private static final Property DCTERMS_IDENTIFIER = ResourceFactory.createProperty(NS_DCTERMS, "identifier");
    private static final Property DCTERMS_ISSUED = ResourceFactory.createProperty(NS_DCTERMS, "issued");
    private static final Property DCTERMS_LICENSE = ResourceFactory.createProperty(NS_DCTERMS, "license");
    private static final Property DCTERMS_PUBLISHER = ResourceFactory.createProperty(NS_DCTERMS, "publisher");
    private static final Property DCTERMS_REQUIRES = ResourceFactory.createProperty(NS_DCTERMS, "requires");
    private static final Property DCTERMS_RIGHTS = ResourceFactory.createProperty(NS_DCTERMS, "rights");
    private static final Property DCTERMS_RIGHTS_HOLDER = ResourceFactory.createProperty(NS_DCTERMS, "rightsHolder");
    private static final Property DCTERMS_SPATIAL = ResourceFactory.createProperty(NS_DCTERMS, "spatial");
    private static final Property DCTERMS_TITLE = ResourceFactory.createProperty(NS_DCTERMS, "title");
    private static final Property DCTERMS_TYPE = ResourceFactory.createProperty(NS_DCTERMS, "type");

    private static final Property ADMS_VERSION_NOTES = ResourceFactory.createProperty(NS_ADMS, "versionNotes");

    private static final Property DCAT_ACCESS_URL = ResourceFactory.createProperty(NS_DCAT, "accessURL");
    private static final Property DCAT_DISTRIBUTION_OF = ResourceFactory.createProperty(NS_DCAT, "distributionOf");
    private static final Property DCAT_DATASET_PROPERTY = ResourceFactory.createProperty(NS_DCAT, "dataset");
    private static final Property DCAT_IS_VERSION_OF = ResourceFactory.createProperty(NS_DCAT, "isVersionOf");
    private static final Property DCAT_MEDIA_TYPE = ResourceFactory.createProperty(NS_DCAT, "mediaType");
    private static final Property DCAT_VERSION = ResourceFactory.createProperty(NS_DCAT, "version");

    private static final Property PROV_GENERATED_AT_TIME = ResourceFactory.createProperty(NS_PROV, "generatedAtTime");
    private static final Property PROV_WAS_GENERATED_BY = ResourceFactory.createProperty(NS_PROV, "wasGeneratedBy");

    private static final Property MD_MODEL_CREATED = ResourceFactory.createProperty(NS_MD, "Model.created");
    private static final Property MD_MODEL_DESCRIPTION = ResourceFactory.createProperty(NS_MD, "Model.description");
    private static final Property MD_MODEL_DEPENDENT_ON = ResourceFactory.createProperty(NS_MD, "Model.DependentOn");
    private static final Property MD_MODEL_MODELING_AUTHORITY_SET = ResourceFactory.createProperty(NS_MD, "Model.modelingAuthoritySet");
    private static final Property MD_MODEL_PROFILE = ResourceFactory.createProperty(NS_MD, "Model.profile");
    private static final Property MD_MODEL_SCENARIO_TIME = ResourceFactory.createProperty(NS_MD, "Model.scenarioTime");
    private static final Property MD_MODEL_VERSION = ResourceFactory.createProperty(NS_MD, "Model.version");

    private static final Resource DEFAULT_MEDIA_TYPE = ResourceFactory.createResource("http://www.iana.org/assignments/media-types/application/rdf+xml");
    private static final Resource DEFAULT_WAS_GENERATED_BY = ResourceFactory.createResource("https://energy.referencedata.eu/Test/Action/PlaceholderGeneration");
    private static final Resource DEFAULT_LICENSE = ResourceFactory.createResource("urn:placeholder:license");
    private static final Resource DEFAULT_ACCESS_RIGHTS = ResourceFactory.createResource("https://energy.referencedata.eu/Confidentiality/Public");
    private static final Resource DEFAULT_TYPE = ResourceFactory.createResource("https://energy.referencedata.eu/type/CIM-PowerSystemModel");
    private static final Resource DEFAULT_SPATIAL = ResourceFactory.createResource("urn:placeholder:spatial");
    private static final Resource DEFAULT_PUBLISHER = ResourceFactory.createResource("urn:placeholder:publisher");

    private ManifestGenerator() {
        // Utility class
    }

    public static Model generateManifest(Model sourceModel, String accessUrl) {
        return generateManifest(Map.of("source.xml", sourceModel), List.of(), accessUrl);
    }

    public static Model generateManifest(Map<String, Model> sourceModels, List<File> sourceFiles, String accessUrl) {
        Model manifest = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        setFixedPrefixes(manifest);

        Resource catalog = createCatalogResource(manifest);
        catalog.addProperty(RDF.type, DCAT_CATALOG);
        catalog.addProperty(DCTERMS_TITLE, "Manifest");
        catalog.addProperty(DCTERMS_DESCRIPTION, plainLiteral("", "en"));
        catalog.addProperty(DCTERMS_IDENTIFIER, catalog.getLocalName());

        List<File> orderedFiles = sourceFiles == null || sourceFiles.isEmpty()
                ? sourceModels.keySet().stream().map(File::new).toList()
                : sourceFiles;

        for (int i = 0; i < orderedFiles.size(); i++) {
            File sourceFile = orderedFiles.get(i);
            Model sourceModel = resolveModel(sourceModels, sourceFile);
            if (sourceModel == null) {
                continue;
            }

            Resource datasetHeader = findHeader(sourceModel, DCAT.Dataset);
            Resource fullModelHeader = findHeader(sourceModel, MD_FULL_MODEL);

            Resource dataset = createDatasetResource(manifest, datasetHeader, fullModelHeader, sourceFile);
            Resource distribution = createDistributionResource(manifest, sourceModel, datasetHeader, sourceFile, dataset);

            dataset.addProperty(RDF.type, DCAT_DATASET);
            dataset.addProperty(DCAT.distribution, distribution);
            catalog.addProperty(DCAT_DATASET_PROPERTY, dataset);

            distribution.addProperty(RDF.type, DCAT_DISTRIBUTION);
            distribution.addProperty(DCAT_DISTRIBUTION_OF, dataset);

            // NCP terms are copied directly; CGMES md:* fields are mapped into their closest manifest terms.
            copyBest(dataset, DCTERMS_ACCESS_RIGHTS,
                    values(sourceModel, datasetHeader, DCTERMS_ACCESS_RIGHTS),
                    List.of(DEFAULT_ACCESS_RIGHTS));
            copyBest(dataset, DCTERMS_CONFORMS_TO,
                    values(sourceModel, datasetHeader, DCTERMS_CONFORMS_TO),
                    values(sourceModel, fullModelHeader, MD_MODEL_PROFILE));
            copyBest(dataset, DCTERMS_DESCRIPTION,
                    values(sourceModel, datasetHeader, DCTERMS_DESCRIPTION),
                    values(sourceModel, fullModelHeader, MD_MODEL_DESCRIPTION));
            copyBest(dataset, DCTERMS_IDENTIFIER,
                    values(sourceModel, datasetHeader, DCTERMS_IDENTIFIER),
                    List.of(ResourceFactory.createPlainLiteral(dataset.getLocalName())));
            copyBest(dataset, DCTERMS_ISSUED,
                    values(sourceModel, datasetHeader, DCTERMS_ISSUED),
                    values(sourceModel, fullModelHeader, MD_MODEL_CREATED));
            copyBest(dataset, DCTERMS_LICENSE,
                    values(sourceModel, datasetHeader, DCTERMS_LICENSE),
                    List.of(DEFAULT_LICENSE));
            copyBest(dataset, DCTERMS_PUBLISHER,
                    values(sourceModel, datasetHeader, DCTERMS_PUBLISHER),
                    values(sourceModel, fullModelHeader, MD_MODEL_MODELING_AUTHORITY_SET),
                    List.of(DEFAULT_PUBLISHER));
            copyBest(dataset, DCTERMS_REQUIRES,
                    values(sourceModel, datasetHeader, DCTERMS_REQUIRES),
                    values(sourceModel, fullModelHeader, MD_MODEL_DEPENDENT_ON));
            copyBest(dataset, DCTERMS_RIGHTS,
                    values(sourceModel, datasetHeader, DCTERMS_RIGHTS),
                    List.of(plainLiteral("Copyright")));
            copyBest(dataset, DCTERMS_RIGHTS_HOLDER,
                    values(sourceModel, datasetHeader, DCTERMS_RIGHTS_HOLDER),
                    List.of(plainLiteral("PLACEHOLDER_RIGHTS_HOLDER")));
            copyBest(dataset, DCTERMS_SPATIAL,
                    values(sourceModel, datasetHeader, DCTERMS_SPATIAL),
                    List.of(DEFAULT_SPATIAL));
            copyBest(dataset, DCTERMS_TITLE,
                    values(sourceModel, datasetHeader, DCTERMS_TITLE),
                    List.of(plainLiteral(stripExtension(sourceFile.getName()))));
            copyBest(dataset, DCTERMS_TYPE,
                    values(sourceModel, datasetHeader, DCTERMS_TYPE),
                    List.of(DEFAULT_TYPE));

            copyBest(dataset, DCAT_IS_VERSION_OF,
                    values(sourceModel, datasetHeader, DCAT_IS_VERSION_OF));
            copyBest(dataset, DCAT.keyword,
                    values(sourceModel, datasetHeader, DCAT.keyword),
                    keywordFromModel(sourceModel));
            copyBest(dataset, DCAT.startDate,
                    values(sourceModel, datasetHeader, DCAT.startDate),
                    values(sourceModel, fullModelHeader, MD_MODEL_SCENARIO_TIME));
            copyBest(dataset, DCAT.endDate,
                    values(sourceModel, datasetHeader, DCAT.endDate));
            copyBest(dataset, DCAT_VERSION,
                    values(sourceModel, datasetHeader, DCAT_VERSION),
                    values(sourceModel, fullModelHeader, MD_MODEL_VERSION));

            copyBest(distribution, DCTERMS_CONFORMS_TO,
                    values(sourceModel, distributionFromDataset(sourceModel, datasetHeader), DCTERMS_CONFORMS_TO),
                    values(sourceModel, datasetHeader, DCTERMS_CONFORMS_TO),
                    values(sourceModel, fullModelHeader, MD_MODEL_PROFILE));
            copyBest(distribution, DCTERMS_DESCRIPTION,
                    values(sourceModel, distributionFromDataset(sourceModel, datasetHeader), DCTERMS_DESCRIPTION),
                    values(sourceModel, datasetHeader, DCTERMS_DESCRIPTION),
                    values(sourceModel, fullModelHeader, MD_MODEL_DESCRIPTION));

            Resource accessUrlRes = coalesceResource(
                    values(sourceModel, distributionFromDataset(sourceModel, datasetHeader), DCAT_ACCESS_URL),
                    i == 0 ? compactAccessUrlValues(accessUrl, sourceFile) : List.of(),
                    compactAccessUrlValues(null, sourceFile)
            );
            if (accessUrlRes != null) {
                distribution.addProperty(DCAT_ACCESS_URL, accessUrlRes);
            }

            Resource mediaTypeRes = coalesceResource(
                    values(sourceModel, distributionFromDataset(sourceModel, datasetHeader), DCAT_MEDIA_TYPE),
                    List.of(DEFAULT_MEDIA_TYPE)
            );
            distribution.addProperty(DCAT_MEDIA_TYPE, mediaTypeRes);

            copyBest(distribution, PROV_GENERATED_AT_TIME,
                    values(sourceModel, distributionFromDataset(sourceModel, datasetHeader), PROV_GENERATED_AT_TIME),
                    values(sourceModel, datasetHeader, PROV_GENERATED_AT_TIME),
                    values(sourceModel, fullModelHeader, MD_MODEL_CREATED),
                    List.of(nowLiteral()));

            RDFNode generatedBy = coalesceNode(
                    values(sourceModel, distributionFromDataset(sourceModel, datasetHeader), PROV_WAS_GENERATED_BY),
                    values(sourceModel, datasetHeader, PROV_WAS_GENERATED_BY),
                    List.of(DEFAULT_WAS_GENERATED_BY)
            );
            if (generatedBy != null) {
                distribution.addProperty(PROV_WAS_GENERATED_BY, generatedBy);
            }
        }

        // Catalog inherits key governance metadata from generated datasets.
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_ACCESS_RIGHTS, List.of(DEFAULT_ACCESS_RIGHTS));
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_CONFORMS_TO, List.of(ResourceFactory.createResource("urn:placeholder:conformsTo")));
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_ISSUED, List.of(nowLiteral()));
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_LICENSE, List.of(DEFAULT_LICENSE));
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_PUBLISHER, List.of(DEFAULT_PUBLISHER));
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_RIGHTS, List.of(plainLiteral("Copyright")));
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_RIGHTS_HOLDER, List.of(plainLiteral("PLACEHOLDER_RIGHTS_HOLDER")));
        copyCatalogFromDatasets(manifest, catalog, DCTERMS_SPATIAL, List.of(DEFAULT_SPATIAL));
        copyCatalogFromDatasets(manifest, catalog, ADMS_VERSION_NOTES, List.of(plainLiteral("Placeholder version notes.", "en")));
        copyCatalogFromDatasets(manifest, catalog, DCAT_VERSION, List.of(plainLiteral("PLACEHOLDER_VERSION")));

        return manifest;
    }

    public static Model generateManifest(Map<String, Model> sourceModels, String accessUrl) {
        return generateManifest(sourceModels, List.of(), accessUrl);
    }

    public static void generateManifestTtl(Model sourceModel, String accessUrl, OutputStream outputStream) {
        Model manifest = generateManifest(sourceModel, accessUrl);
        manifest.write(outputStream, "TURTLE");
    }

    public static void generateManifestTtl(Map<String, Model> sourceModels, List<File> sourceFiles, String accessUrl, OutputStream outputStream) {
        Model manifest = generateManifest(sourceModels, sourceFiles, accessUrl);
        manifest.write(outputStream, "TURTLE");
    }

    public static void generateManifestTtl(Model sourceModel, String accessUrl, File outputFile) throws IOException {
        try (OutputStream os = new FileOutputStream(outputFile)) {
            generateManifestTtl(sourceModel, accessUrl, os);
        }
    }

    public static void generateManifestTtl(Map<String, Model> sourceModels, List<File> sourceFiles, String accessUrl, File outputFile) throws IOException {
        try (OutputStream os = new FileOutputStream(outputFile)) {
            generateManifestTtl(sourceModels, sourceFiles, accessUrl, os);
        }
    }

    public static String generateManifestTtlString(Model sourceModel, String accessUrl) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        generateManifestTtl(sourceModel, accessUrl, baos);
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static void setFixedPrefixes(Model model) {
        model.setNsPrefix("adms", NS_ADMS);
        model.setNsPrefix("cim", NS_CIM);
        model.setNsPrefix("dcat", NS_DCAT);
        model.setNsPrefix("datacim", NS_DATACIM);
        model.setNsPrefix("dcterms", NS_DCTERMS);
        model.setNsPrefix("eu", NS_EU);
        model.setNsPrefix("nc", NS_NC);
        model.setNsPrefix("prov", NS_PROV);
        model.setNsPrefix("rdf", NS_RDF);
    }

    private static Resource findHeader(Model model, Resource type) {
        ResIterator it = model.listSubjectsWithProperty(RDF.type, type);
        return it.hasNext() ? it.next() : null;
    }

    private static Resource createDatasetResource(Model manifest, Resource datasetHeader, Resource fullModelHeader) {
        return createDatasetResource(manifest, datasetHeader, fullModelHeader, null);
    }

    private static Resource createDatasetResource(Model manifest, Resource datasetHeader, Resource fullModelHeader, File sourceFile) {
        if (datasetHeader != null && datasetHeader.isURIResource()) {
            return manifest.createResource(datasetHeader.getURI());
        }
        if (fullModelHeader != null && fullModelHeader.isURIResource()) {
            return manifest.createResource(fullModelHeader.getURI());
        }
        return manifest.createResource("urn:uuid:" + UUID.randomUUID());
    }

    private static Resource createDistributionResource(Model manifest, Model sourceModel, Resource datasetHeader) {
        return createDistributionResource(manifest, sourceModel, datasetHeader, null, null);
    }

    private static Resource createDistributionResource(Model manifest, Model sourceModel, Resource datasetHeader, File sourceFile, Resource dataset) {
        Resource existingDist = distributionFromDataset(sourceModel, datasetHeader);
        if (existingDist != null && existingDist.isURIResource()) {
            return manifest.createResource(existingDist.getURI());
        }
        return manifest.createResource("urn:uuid:" + UUID.randomUUID());
    }

    private static Resource distributionFromDataset(Model sourceModel, Resource datasetHeader) {
        if (datasetHeader == null) {
            return null;
        }
        StmtIterator it = sourceModel.listStatements(datasetHeader, DCAT.distribution, (RDFNode) null);
        while (it.hasNext()) {
            RDFNode node = it.next().getObject();
            if (node.isResource()) {
                return node.asResource();
            }
        }
        return null;
    }

    @SafeVarargs
    private static void copyBest(Resource subject, Property property, List<RDFNode>... candidateLists) {
        List<RDFNode> values = firstNonEmpty(candidateLists);
        for (RDFNode value : values) {
            subject.addProperty(property, value);
        }
    }

    @SafeVarargs
    private static List<RDFNode> firstNonEmpty(List<RDFNode>... candidateLists) {
        for (List<RDFNode> list : candidateLists) {
            if (list != null && !list.isEmpty()) {
                return list;
            }
        }
        return List.of();
    }

    @SafeVarargs
    private static RDFNode coalesceNode(List<RDFNode>... candidateLists) {
        List<RDFNode> first = firstNonEmpty(candidateLists);
        return first.isEmpty() ? null : first.getFirst();
    }

    @SafeVarargs
    private static Resource coalesceResource(List<RDFNode>... candidateLists) {
        RDFNode node = coalesceNode(candidateLists);
        if (node == null) {
            return null;
        }
        if (node.isResource()) {
            return node.asResource();
        }
        String uri = node.toString();
        return uri.isBlank() ? null : ResourceFactory.createResource(uri);
    }

    private static List<RDFNode> values(Model model, Resource subject, Property property) {
        if (model == null || subject == null || property == null) {
            return List.of();
        }
        List<RDFNode> values = new ArrayList<>();
        StmtIterator it = model.listStatements(subject, property, (RDFNode) null);
        while (it.hasNext()) {
            values.add(it.next().getObject());
        }
        return values;
    }

    private static List<RDFNode> keywordFromModel(Model model) {
        String keyword = ModelFactory.getProfileKeyword(model);
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return List.of(ResourceFactory.createPlainLiteral(keyword));
    }

    private static Resource createCatalogResource(Model manifest) {
        return manifest.createResource("urn:uuid:" + UUID.randomUUID());
    }

    private static void copyCatalogFromDatasets(Model manifest, Resource catalog, Property property, List<RDFNode> fallbackValues) {
        LinkedHashSet<RDFNode> values = new LinkedHashSet<>();
        StmtIterator dsIt = manifest.listStatements(catalog, DCAT_DATASET_PROPERTY, (RDFNode) null);
        while (dsIt.hasNext()) {
            RDFNode dsNode = dsIt.next().getObject();
            if (!dsNode.isResource()) {
                continue;
            }
            StmtIterator vIt = manifest.listStatements(dsNode.asResource(), property, (RDFNode) null);
            while (vIt.hasNext()) {
                values.add(vIt.next().getObject());
            }
        }

        boolean hasConcreteValue = values.stream().anyMatch(v -> !isPlaceholderNode(v));
        if (hasConcreteValue) {
            values.removeIf(ManifestGenerator::isPlaceholderNode);
        }

        if (values.isEmpty()) {
            values.addAll(fallbackValues);
        }

        for (RDFNode value : values) {
            catalog.addProperty(property, value);
        }
    }

    private static Model resolveModel(Map<String, Model> sourceModels, File sourceFile) {
        if (sourceModels == null || sourceModels.isEmpty() || sourceFile == null) {
            return sourceModels != null && sourceModels.size() == 1 ? sourceModels.values().iterator().next() : null;
        }
        Model model = sourceModels.get(sourceFile.getName());
        if (model != null) {
            return model;
        }
        return sourceModels.get(stripExtension(sourceFile.getName()));
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "manifest";
        }
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(0, idx) : name;
    }

    private static Literal plainLiteral(String value) {
        return ResourceFactory.createPlainLiteral(value);
    }

    private static Literal plainLiteral(String value, String lang) {
        return ResourceFactory.createLangLiteral(value, lang);
    }

    private static Literal nowLiteral() {
        return ResourceFactory.createTypedLiteral(Instant.now().toString(), XSDDatatype.XSDdateTime);
    }

    private static boolean isPlaceholderNode(RDFNode node) {
        if (node == null) {
            return false;
        }
        if (node.isResource()) {
            Resource r = node.asResource();
            return r.isURIResource() && r.getURI().startsWith("urn:placeholder:");
        }
        if (node.isLiteral()) {
            String text = node.asLiteral().getLexicalForm();
            return text.startsWith("PLACEHOLDER_") || text.startsWith("Placeholder ");
        }
        return false;
    }

    private static List<RDFNode> compactAccessUrlValues(String rawAccessUrl, File sourceFile) {
        String compact = compactAccessUrl(rawAccessUrl, sourceFile);
        if (compact == null || compact.isBlank()) {
            return List.of();
        }
        return List.of(ResourceFactory.createResource(compact));
    }

    private static String compactAccessUrl(String rawAccessUrl, File sourceFile) {
        String parentFolder = null;
        String fileName = null;

        if (rawAccessUrl != null && !rawAccessUrl.isBlank()) {
            try {
                URI uri = URI.create(rawAccessUrl.trim());
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    Path p = Path.of(uri);
                    fileName = p.getFileName() != null ? p.getFileName().toString() : null;
                    Path parent = p.getParent();
                    parentFolder = (parent != null && parent.getFileName() != null) ? parent.getFileName().toString() : null;
                }
            } catch (Exception ignored) {
                // Fall back to selected file info.
            }
        }

        if ((fileName == null || fileName.isBlank()) && sourceFile != null) {
            fileName = sourceFile.getName();
            File parent = sourceFile.getParentFile();
            parentFolder = parent != null ? parent.getName() : null;
        }

        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        String safeFileName = fileName.replace("\\", "/").replace(" ", "%20");
        if (parentFolder == null || parentFolder.isBlank()) {
            return "file://" + safeFileName;
        }
        String safeParentFolder = parentFolder.replace("\\", "/").replace(" ", "%20");
        return "file://" + safeParentFolder + "/" + safeFileName;
    }
}
