package io.syndesis.qe.marketplace.manifests;

import static io.syndesis.qe.marketplace.util.HelperFunctions.readResource;
import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

import static com.jayway.jsonpath.Criteria.where;

import io.syndesis.qe.marketplace.openshift.OpenShiftService;
import io.syndesis.qe.marketplace.util.HelperFunctions;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.Filter;
import com.jayway.jsonpath.JsonPath;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Bundle {
    @Getter
    private final String imageName;
    private static final String CONTAINER_TOOL = "docker";
    @Getter
    private Map<String, String> annotations;
    @Getter
    private JSONObject csv;

    @Getter
    private List<String> crds;
    private Index index;

    private String subscriptionName;

    Bundle(String imageName, Index index) {
        this.imageName = imageName;
        this.index = index;
        readMetadata();
    }

    private static CustomResourceDefinitionContext subscriptionContext() {
        return new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("subscriptions")
            .withScope("Namespaced")
            .withVersion("v1alpha1")
            .build();
    }

    private static CustomResourceDefinitionContext installPlanContext() {
        return new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("installplans")
            .withScope("Namespaced")
            .withVersion("v1alpha1")
            .build();
    }

    private static void unTar(TarArchiveInputStream tis, File destFolder) throws IOException {
        TarArchiveEntry tarEntry = null;
        while ((tarEntry = tis.getNextTarEntry()) != null) {
            if (tarEntry.isDirectory()) {
                new File(destFolder, tarEntry.getName()).mkdirs();
            } else {
                File result = new File(destFolder, tarEntry.getName());
                FileOutputStream fos = new FileOutputStream(result);
                IOUtils.copy(tis, fos);
                if (result.getName().endsWith(".tar")) {
                    unTar(new TarArchiveInputStream(new FileInputStream(result)), destFolder);
                }
                fos.close();
            }
        }
        tis.close();
    }

    @SneakyThrows
    private static String readFile(String path) {
        return IOUtils.toString(new FileInputStream(path), Charset.defaultCharset());
    }

    private void consumeManifestFolder(File manifestFolder) {
        String[] manifests = manifestFolder.list();
        Optional<String> csvPath = Stream.of(manifests)
            .filter(s -> s.contains("clusterserviceversion.yaml"))
            .findFirst();
        if (csvPath.isPresent()) {
            String csvSource = readFile(Paths.get(manifestFolder.getAbsolutePath(), csvPath.get()).toString());

            csv = new JSONObject((Map<String, Object>)new Yaml().load(csvSource));
        } else {
            throw new IllegalStateException(
                "A csv entry is missing from the bundle " + imageName + " take a look at folder " + manifestFolder.getParentFile().toString());
        }
        crds = Stream.of(manifests)
            .filter(s -> s.contains("crd.yaml"))
            .map(s -> Paths.get(manifestFolder.getAbsolutePath(), s).toString())
            .map(Bundle::readFile)
            .collect(Collectors.toList());
    }

    @SneakyThrows
    private void readMetadata() {
        Path tmpFolder = Files.createTempDirectory("bundle");
        String outputPath = tmpFolder.toAbsolutePath() + File.separator + "bundle.tar";
        HelperFunctions.runCmd(CONTAINER_TOOL, "pull", imageName);
        HelperFunctions.runCmd(CONTAINER_TOOL, "save", imageName, "-o=" + outputPath);

        try (TarArchiveInputStream inputStream = new TarArchiveInputStream(new FileInputStream(outputPath))) {
            unTar(inputStream, tmpFolder.toFile());
        }
        log.info("Unzipped archive: {}", tmpFolder.toAbsolutePath());

        File annotationsFile = new File(tmpFolder.toFile(), Paths.get("metadata", "annotations.yaml").toString());
        this.annotations = new Yaml().<Map<String, Map<String, String>>>load(new FileInputStream(annotationsFile)).get("annotations");

        File manifestFolder = new File(tmpFolder.toFile(), getManifestFolder());
        consumeManifestFolder(manifestFolder);
    }

    public void createSubscription(OpenShiftService service) throws IOException {
        createSubscription(service, getPackageName(), getDefaultChannel(), getCSVName());
    }

    private void createOperatorGroup(OpenShiftService service) throws IOException {
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();

        CustomResourceDefinitionContext operatorGroupCrdContext = new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withPlural("operatorgroups")
            .withScope("Namespaced")
            .withVersion("v1alpha2")
            .build();

        String operatorGroupYaml = readResource("openshift/create-operatorgroup.yaml")
            .replaceAll("OPENSHIFT_PROJECT", namespace);

        ocp.customResource(operatorGroupCrdContext).createOrReplace(namespace, operatorGroupYaml);
    }

    public void createSubscription(OpenShiftService service, String name, String channel, String startingCSV) throws IOException {
        service.setupImageContentSourcePolicy();
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();
        String subscription = HelperFunctions.readResource("openshift/create-subscriptionindex.yaml");
        subscription = subscription.replaceAll("NAMESPACE", namespace)
            .replaceAll("CHANNEL", channel)
            .replaceAll("STARTING_CSV", startingCSV)
            .replaceAll("NAME", name)
            .replaceAll("SOURCE", index.getOcpName());

        subscriptionName = name;

        if (ocp.getProject(namespace) == null) {
            ocp.createProjectRequest(namespace);
        }
        createOperatorGroup(service);
        ocp.customResource(subscriptionContext()).createOrReplace(namespace, subscription);
    }

    @SneakyThrows
    public static void createSubscription(OpenShiftService service, String name, String channel, String startingCSV, String source) {
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();
        String subscription = HelperFunctions.readResource("openshift/create-subscriptionindex.yaml");
        subscription = subscription.replaceAll("NAMESPACE", namespace)
            .replaceAll("CHANNEL", channel)
            .replaceAll("STARTING_CSV", startingCSV)
            .replaceAll("NAME", name)
            .replaceAll("SOURCE", source);

        ocp.customResource(subscriptionContext()).createOrReplace(namespace, subscription);
    }

    @SneakyThrows
    public void update(OpenShiftService service, Bundle newBundle) {
        OpenShift ocp = service.getClient();
        String namespace = service.getClient().getNamespace();

        JSONObject subscription = new JSONObject(ocp.customResource(subscriptionContext()).get(namespace, subscriptionName));
        subscription.getJSONObject("spec").put("channel", newBundle.getDefaultChannel());
        waitFor(() -> {
            try {
                ocp.customResource(subscriptionContext()).edit(namespace, subscriptionName, subscription.toString());
                return true;
            } catch (Exception e){
                return false;
            }
        }, 5000, 1000);
    }

    @SneakyThrows
    public void update(OpenShiftService service, Bundle newBundle, boolean wait) {
        update(service, newBundle);

        if (wait){
            waitForUpdate(service, newBundle);
        }
    }

    @SneakyThrows
    public void waitForUpdate(OpenShiftService service, Bundle newBundle) {
        OpenShift ocp = service.getClient();


        final String previousCSV = getCSVName();
        final String newCSV = newBundle.getCSVName();

        Filter completeFilter = Filter.filter(
            where("phase").is("Complete")
        );

        Filter matchesCSVs = Filter.filter(
            where("bundleLookups.identifier").eq(newCSV).and("bundleLookups.replaces").eq(previousCSV)
        );

        waitFor(() -> {
            final DocumentContext documentContext = JsonPath.parse(ocp.customResource(installPlanContext()).list(ocp.getNamespace()));

            //Find all Complete installplans
            final Object read = documentContext
                .read("$.items[*].status[?]", completeFilter);

            //From the complete installplans find one that matches the CSVs
            final Object found = JsonPath.parse(read).read("$", matchesCSVs);

            return found != null;
        }, 2, 120 * 100);

    }

    public String getDefaultChannel() {
        String val = annotations.get("operators.operatorframework.io.bundle.channel.default.v1");
        return val == null ? getChannels()[0] : val;
    }

    public String[] getChannels() {
        return annotations.get("operators.operatorframework.io.bundle.channels.v1").split(",");
    }

    public String getMediaType() {
        return annotations.get("operators.operatorframework.io.bundle.mediatype.v1");
    }

    public String getPackageName() {
        return annotations.get("operators.operatorframework.io.bundle.package.v1");
    }

    private String getManifestFolder() {
        return annotations.get("operators.operatorframework.io.bundle.manifests.v1");
    }

    private String getMetadataFolder() {
        return annotations.get("operators.operatorframework.io.bundle.metadata.v1");
    }

    public String getCSVName() {
        return csv.getJSONObject("metadata").getString("name");
    }
}
