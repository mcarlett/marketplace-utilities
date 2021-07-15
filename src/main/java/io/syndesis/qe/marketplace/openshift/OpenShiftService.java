package io.syndesis.qe.marketplace.openshift;

import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenShiftService {

    private final OpenShift openShiftClient;

    @Getter
    private final OpenShiftConfiguration openShiftConfiguration;
    @Getter
    private final OpenShiftUser adminUser;

    @Getter
    private OpenShift openShiftClientAsRegularUser;

    public OpenShiftService(
        OpenShiftConfiguration openShiftConfiguration,
        OpenShiftUser adminOpenShiftUser,
        OpenShiftUser regularOpenShiftUser) {

        this.openShiftConfiguration = openShiftConfiguration;

        this.openShiftClient = OpenShift.get(
            adminOpenShiftUser.getApiUrl(),
            openShiftConfiguration.getNamespace(),
            adminOpenShiftUser.getUserName(),
            adminOpenShiftUser.getPassword()
        );
        this.adminUser = adminOpenShiftUser;

        if (regularOpenShiftUser != null) {
            openShiftClientAsRegularUser = OpenShift.get(
                regularOpenShiftUser.getApiUrl(),
                openShiftConfiguration.getNamespace(),
                regularOpenShiftUser.getUserName(),
                regularOpenShiftUser.getPassword()
            );
        }
    }

    public void refreshOperators() {
        openShiftClient.pods().inNamespace("openshift-marketplace")
            .delete();
    }

    @SneakyThrows
    public void setupImageContentSourcePolicy() {
        CustomResourceDefinitionContext mcpContext = new CustomResourceDefinitionContext.Builder()
            .withVersion("v1")
            .withGroup("machineconfiguration.openshift.io")
            .withScope("Cluster")
            .withPlural("machineconfigpools")
            .build();
        //make sure nodes are not being updated
        waitFor(() -> {
            try {
                JSONObject mcpList = new JSONObject(openShiftClient.customResource(mcpContext).list());
                final JSONArray mcps = mcpList.getJSONArray("items");
                boolean updated = false;
                for (int i = 0; i < mcps.length(); i++) {
                    final DocumentContext documentContext = JsonPath.parse(mcps.getJSONObject(i).toString());
                    boolean status =
                        documentContext.read(".status.conditions[?(@.type==\"Updated\")].status").toString().toLowerCase().contains("true");
                    updated |= status;
                }
                return updated;
            } catch (Exception ex) {
                log.error("Got exception in the wait: ", ex);
                return false;
            }
        }, 10, 3000);
        if (openShiftConfiguration.getIcspFile() != null && openShiftConfiguration.getDockerRegistry() != null) {
            final CustomResourceDefinitionContext icspContext =
                new CustomResourceDefinitionContext.Builder().withGroup("operator.openshift.io").withPlural("imagecontentsourcepolicies")
                    .withScope("Cluster").withVersion("v1alpha1").build();
            final String icspSource = IOUtils.toString(new URL(openShiftConfiguration.getIcspFile()), StandardCharsets.UTF_8);
            final String icspName = ((Map<String, String>) ((Map<String, Object>) new Yaml().load(icspSource)).get("metadata")).get("name");
            try {
                openShiftClient.customResource(icspContext).get(icspName);
            } catch (KubernetesClientException ignored) {
                log.info("ICSP was not found, creating new!");
                try {
                    openShiftClient.customResource(icspContext).createOrReplace(icspSource);
                } catch (Exception ex) {
                    log.error("Something went wrong while setting up ICSP, would you mind setting it manually?", ex);
                    throw new RuntimeException(ex);
                }
                waitForMCP();
            }
        }

        final String globalPullSecret = new String(Base64.decodeBase64(
            openShiftClient.secrets().inNamespace("openshift-config").withName("pull-secret").get().getData().get(".dockerconfigjson")));
        if (!globalPullSecret.contains(openShiftConfiguration.getDockerRegistry())) {
            final JSONObject secretJson = new JSONObject(globalPullSecret);
            JSONObject auth = new JSONObject();
            String authString = openShiftConfiguration.getDockerUsername() + ":" + openShiftConfiguration.getDockerPassword();
            auth.put("username", openShiftConfiguration.getDockerUsername());
            auth.put("password", openShiftConfiguration.getDockerPassword());
            auth.put("auth", Base64.encodeBase64String(authString.getBytes(StandardCharsets.UTF_8)));
            secretJson.getJSONObject("auths").put(openShiftConfiguration.getDockerRegistry(), auth);
            log.info("Setting up global pull-secret");
            openShiftClient.secrets().inNamespace("openshift-config").withName("pull-secret").edit()
                .addToData(".dockerconfigjson", new String(Base64.encodeBase64(secretJson.toString().getBytes())))
                .done();
            waitForMCP();
        }
    }

    private void waitForMCP() throws InterruptedException, TimeoutException {
        CustomResourceDefinitionContext mcpContext = new CustomResourceDefinitionContext.Builder()
            .withVersion("v1")
            .withGroup("machineconfiguration.openshift.io")
            .withScope("Cluster")
            .withPlural("machineconfigpools")
            .build();

        log.info("Waiting for OCP to pick up new config, this might take a while...");
        waitFor(() -> {
            JSONObject mcpList = new JSONObject(openShiftClient.customResource(mcpContext).list());
            final JSONArray mcps = mcpList.getJSONArray("items");
            boolean updating = false;
            for (int i = 0; i < mcps.length(); i++) {
                final DocumentContext documentContext = JsonPath.parse(mcps.getJSONObject(i).toString());
                boolean status =
                    documentContext.read(".status.conditions[?(@.type==\"Updating\")].status").toString().toLowerCase().contains("true");
                updating |= status;
            }
            return updating;
        }, 10, 3000);

        log.info("Nodes started upgrading, this will also take a while...");
        waitFor(() -> {
            try {
                JSONObject mcpList = new JSONObject(openShiftClient.customResource(mcpContext).list());
                final JSONArray mcps = mcpList.getJSONArray("items");
                boolean updated = false;
                for (int i = 0; i < mcps.length(); i++) {
                    final DocumentContext documentContext = JsonPath.parse(mcps.getJSONObject(i).toString());
                    boolean status =
                        documentContext.read(".status.conditions[?(@.type==\"Updated\")].status").toString().toLowerCase().contains("true");
                    updated |= status;
                }
                return updated;
            } catch (Exception ex) {
                log.error("Got exception in the wait: ", ex);
                return false;
            }
        }, 10, 3000);
    }

    /**
     * Configures openshift-marketplace to pull from Quay and mirror internal registries
     *
     * @param pullSecretContent - base64 encoded Docker auths json
     */
    public void patchGlobalSecrets(String pullSecretContent) {
        Map<String, String> obligatoryMap = new HashMap<>();
        obligatoryMap.put(".dockerconfigjson", pullSecretContent);

        Secret s = openShiftClient.inNamespace("openshift-marketplace").secrets().createOrReplaceWithNew()
            .withType("kubernetes.io/dockerconfigjson")
            .editOrNewMetadata()
            .withName("quay-pull-secret")
            .withNamespace("openshift-marketplace")
            .endMetadata()
            .withData(obligatoryMap)
            .done();

        openShiftClient.serviceAccounts().inNamespace("openshift-marketplace").withName("default").edit()
            .addNewSecret()
            .withName(s.getMetadata().getName())
            .withNamespace(s.getMetadata().getNamespace())
            .endSecret()
            .done();
    }

    public OpenShift getClient() {
        return openShiftClient;
    }
}
