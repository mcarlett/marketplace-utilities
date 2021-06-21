package io.syndesis.qe.marketplace.manifests;

import static io.syndesis.qe.marketplace.util.HelperFunctions.readResource;
import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

import io.syndesis.qe.marketplace.openshift.OpenShiftService;
import io.syndesis.qe.marketplace.quay.QuayService;
import io.syndesis.qe.marketplace.quay.QuayUser;
import io.syndesis.qe.marketplace.util.HelperFunctions;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.openshift.client.OpenShiftClient;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Index {
    private final String name;
    @Getter
    private String ocpName;
    private List<Bundle> bundles;
    private Opm opm;
    private static final String BUILD_TOOL = System.getProperty("marketplace.build.tool", "docker");
    static final String MARKETPLACE_NAMESPACE = "openshift-marketplace";
    private static QuayService quaySvc;
    private static File configFile;

    private QuayUser quayUser;

    Index(String name, Opm opm, QuayUser quayUser) {
        this.name = name;
        bundles = new ArrayList<>();
        this.opm = opm;
        this.quayUser = quayUser;
    }

    void createConfig(QuayUser user) {
        try {
            File configFolder = Files.createTempDirectory("marketplace-docker-config").toFile();
            configFile = new File(configFolder, "config.json");
            String auth = user.getUserName() + ":" + user.getPassword();
            String encodedAuth = new String(Base64.getEncoder().encode(auth.getBytes()));
            String contents = "{\"auths\": {\"quay.io\": {\"auth\": \"" + encodedAuth + "\"}}}";
            try (FileWriter fileWriter = new FileWriter(configFile)) {
                IOUtils.write(contents, fileWriter);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void pull() {
        HelperFunctions.runCmd(BUILD_TOOL, "pull", this.name);
    }

    public Bundle addBundle(String bundleName) {
        if (bundles.isEmpty()) {
            opm.runOpmCmd("index", "add", "--bundles=" + bundleName, "--tag=" + this.name, "--build-tool=" + BUILD_TOOL);
        } else {
            opm.runOpmCmd("index", "add", "--bundles=" + bundleName, "--tag=" + this.name, "--build-tool=" + BUILD_TOOL, "--from-index=" + name);
        }
        Bundle bundle = new Bundle(bundleName, this);
        bundles.add(bundle);
        push();
        return bundle;
    }

    public void addBundles(String... names) {
        for (String name : names) {
            addBundle(name);
        }
    }

    @SneakyThrows
    private void push() {
        if (quaySvc == null) {
            quaySvc = new QuayService(quayUser, null, null);
        }
        if (configFile == null || !configFile.exists()) {
            createConfig(quayUser);
        }
        if (BUILD_TOOL.equalsIgnoreCase("docker")) {
            HelperFunctions.runCmd(BUILD_TOOL, "--config", configFile.getParent(), "push", name);
        } else {
            HelperFunctions.runCmd(BUILD_TOOL, "--authfile", configFile.getParent(), "push", name);
        }
    }

    private static CustomResourceDefinitionContext catalogSourceIndex() {
        return new CustomResourceDefinitionContext.Builder()
            .withGroup("operators.coreos.com")
            .withVersion("v1alpha1")
            .withName("CatalogSource")
            .withPlural("catalogsources")
            .withScope("Namespaced")
            .build();
    }

    public void addIndexToCluster(OpenShiftService service, String catalogName) throws IOException, TimeoutException, InterruptedException {
        OpenShiftClient ocp = service.getClient();

        String catalogSource = null;
        try {
            catalogSource = readResource("openshift/create-operatorsourceindex.yaml");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.ocpName = catalogName;
        catalogSource = catalogSource.replaceAll("IMAGE", name)
            .replaceAll("DISPLAY_NAME", catalogName)
            .replaceAll("NAME", catalogName);
        ocp.customResource(catalogSourceIndex()).createOrReplace(MARKETPLACE_NAMESPACE, catalogSource);
        Predicate<Pod> podFound = pod ->
            pod.getMetadata().getName().startsWith(catalogName)
                && "Running".equalsIgnoreCase(pod.getStatus().getPhase());
        waitFor(() -> ocp.pods().inNamespace("openshift-marketplace").list()
            .getItems().stream().anyMatch(podFound), 5, 60 * 1000);
    }

    public void removeIndexFromCluster(OpenShiftService service) {
        service.getClient().customResource(catalogSourceIndex()).delete(MARKETPLACE_NAMESPACE, ocpName);
    }
}
