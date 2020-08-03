package io.syndesis.qe.marketplace.openshift;

import cz.xtf.core.openshift.OpenShift;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.openshift.api.model.OpenshiftRole;
import io.syndesis.qe.marketplace.util.HelperFunctions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static io.syndesis.qe.marketplace.util.HelperFunctions.readResource;
import static io.syndesis.qe.marketplace.util.HelperFunctions.waitFor;

@Slf4j
public class OpenShiftService {

    private final String quayNamespace;
    private final String quayPackageName;

    private final OpenShift openShiftClient;

    private final OpenShiftConfiguration openShiftConfiguration;

    private OpenShift openShiftClientAsRegularUser;

    public OpenShiftService(
            String quayNamespace,
            String quayPackageName,
            OpenShiftConfiguration openShiftConfiguration,
            OpenShiftUser adminOpenShiftUser,
            OpenShiftUser regularOpenShiftUser) {

        this.quayNamespace = quayNamespace;
        this.quayPackageName = quayPackageName;
        this.openShiftConfiguration = openShiftConfiguration;

        this.openShiftClient = OpenShift.get(
                adminOpenShiftUser.getApiUrl(),
                openShiftConfiguration.getNamespace(),
                adminOpenShiftUser.getUserName(),
                adminOpenShiftUser.getPassword()
        );

        if (regularOpenShiftUser != null) {
            openShiftClientAsRegularUser = OpenShift.get(
                    regularOpenShiftUser.getApiUrl(),
                    openShiftConfiguration.getNamespace(),
                    regularOpenShiftUser.getUserName(),
                    regularOpenShiftUser.getPassword()
            );
        }
    }

    public void deployOperator() throws IOException {
        createNamespace();
        createPullSecret();
        disableDefaultSources();
        createOpsrcToken();
        createOpsrc();
        createOperatorgroup();
        createSubscription();

        DeploymentList deploymentList =
                openShiftClient.apps().deployments().inNamespace(openShiftConfiguration.getNamespace()).list();
        if (deploymentList.getItems().size() != 1) {
            log.error("Must be one deployment, actual number is " + deploymentList.getItems().size());
            throw new IOException("There must be one deployment");
        }

        String operatorResourcesName = deploymentList.getItems().get(0).getMetadata().getName();

        log.info("Operator pod name is '" + operatorResourcesName + "'");

        linkPullSecret(operatorResourcesName);

        log.info("Redeploying operator pod so it uses new pull secret");

        scaleOperatorPod(0, operatorResourcesName);
        scaleOperatorPod(1, operatorResourcesName);
    }

    public void deleteOpsrcToken() {
        log.info("Deleting opsrc token");
        SecretList secretList = openShiftClient.secrets().inNamespace("openshift-marketplace").list();
        for (Secret secret : secretList.getItems()) {
            if (StringUtils.equals(secret.getMetadata().getName(), quayPackageName + "-opsrctoken")) {
                openShiftClient.secrets().inNamespace("openshift-marketplace").delete(secret);
            }
        }
    }

    public void deleteOperatorSource() throws IOException {
        log.info("Deleting operator source for quay package '" + quayPackageName + "'");
        CustomResourceDefinitionContext operatorSourceCrdContext = new CustomResourceDefinitionContext.Builder()
                .withGroup("operators.coreos.com")
                .withPlural("operatorsources")
                .withScope("Namespaced")
                .withVersion("v1")
                .build();

        openShiftClient.customResource(operatorSourceCrdContext)
                .delete("openshift-marketplace", quayPackageName + "-opsrc");
    }

    public void refreshOperators() {
        openShiftClient.pods().inNamespace("openshift-marketplace")
                .delete();
    }

    private void disableDefaultSources() throws IOException {
        log.info("Disabling default sources on openshift");

        CustomResourceDefinitionContext crdContext = new CustomResourceDefinitionContext.Builder()
                .withGroup("config.openshift.io")
                .withPlural("operatorhubs")
                .withScope("Cluster")
                .withVersion("v1")
                .build();

        openShiftClient.customResource(crdContext)
                .createOrReplace("openshift-marketplace",
                        OpenShiftService.class.getResourceAsStream("/openshift/disable-default-sources.yaml"));
    }

    private void createOpsrcToken() throws IOException {
        log.info("Creating operatorsource secret token");
        Map<String, String> data = new HashMap<>();
        data.put("token", openShiftConfiguration.getQuayOpsrcToken());

        openShiftClient.inNamespace("openshift-marketplace").secrets().createOrReplaceWithNew()
                .withNewMetadata()
                .withName(quayPackageName + "-opsrctoken")
                .withNamespace("openshift-marketplace")
                .endMetadata()
                .withData(data)
                .withType("Opaque")
                .done();
    }

    private void createOpsrc() throws IOException {
        log.info("Creating operator source which points toward quay");

        CustomResourceDefinitionContext operatorSourceCrdContext = new CustomResourceDefinitionContext.Builder()
                .withGroup("operators.coreos.com")
                .withPlural("operatorsources")
                .withScope("Namespaced")
                .withVersion("v1")
                .build();

        String operatorSourceYaml = readResource("openshift/create-operatorsource.yaml")
                .replaceAll("PACKAGE_NAME", quayPackageName)
                .replaceAll("QUAY_NAMESPACE", quayNamespace);

        openShiftClient.customResource(operatorSourceCrdContext)
                .createOrReplace("openshift-marketplace",
                        new ByteArrayInputStream(operatorSourceYaml.getBytes(StandardCharsets.UTF_8)));
    }

    private void createNamespace() throws IOException {
        if (openShiftClient.getProject(openShiftConfiguration.getNamespace()) != null) {
            log.info("Namespace exists, deleting namespace first");
            openShiftClient.deleteProject(openShiftConfiguration.getNamespace());
            try {
                HelperFunctions.waitFor(
                        () -> openShiftClient.getProject(openShiftConfiguration.getNamespace()) == null,
                        1, 30);
            } catch (InterruptedException | TimeoutException e) {
                log.error("Namespace was not deleted");
                throw new IOException("Namespace was not created", e);
            }
        }

        log.info("Creating namespace");

        if (openShiftClientAsRegularUser != null) {
            openShiftClientAsRegularUser.createProjectRequest(openShiftConfiguration.getNamespace());
        } else {
            openShiftClient.createProjectRequest(openShiftConfiguration.getNamespace());
        }

        try {
            HelperFunctions.waitFor(
                    () -> openShiftClient.getProject(openShiftConfiguration.getNamespace()) != null,
                    1, 30);
        } catch (InterruptedException | TimeoutException e) {
            log.error("Namespace was not created");
            throw new IOException("Namespace was not created", e);
        }
    }

    private void createPullSecret() throws IOException {
        log.info("Creating pull secret");

        if (openShiftConfiguration.getPullSecret() != null) {
            log.info("Creating a pull secret with name " + openShiftConfiguration.getPullSecretName());
            Map<String, String> pullSecretMap = new HashMap<>();
            pullSecretMap.put(".dockerconfigjson", openShiftConfiguration.getPullSecret());

            openShiftClient.secrets().createOrReplaceWithNew()
                    .withNewMetadata()
                    .withName(openShiftConfiguration.getPullSecretName())
                    .endMetadata()
                    .withData(pullSecretMap)
                    .withType("kubernetes.io/dockerconfigjson")
                    .done();
        }
    }

    private void createOperatorgroup() throws IOException {
        log.info("Creating operatorgroup");

        CustomResourceDefinitionContext operatorGroupCrdContext = new CustomResourceDefinitionContext.Builder()
                .withGroup("operators.coreos.com")
                .withPlural("operatorgroups")
                .withScope("Namespaced")
                .withVersion("v1alpha2")
                .build();

        String operatorGroupYaml = readResource("openshift/create-operatorgroup.yaml")
                .replaceAll("OPENSHIFT_PROJECT", openShiftConfiguration.getNamespace());

        openShiftClient.customResource(operatorGroupCrdContext)
                .createOrReplace(openShiftConfiguration.getNamespace(),
                        new ByteArrayInputStream(operatorGroupYaml.getBytes(StandardCharsets.UTF_8)));
    }

    private void createSubscription() throws IOException {
        log.info("Creating operator subscription");

        String subscriptionYaml = readResource("openshift/create-subscription.yaml")
                .replaceAll("PACKAGE_NAME", quayPackageName)
                .replaceAll("OPENSHIFT_PROJECT", openShiftConfiguration.getNamespace());

        if (openShiftConfiguration.getInstalledCSV() != null) {
            subscriptionYaml = subscriptionYaml.replaceAll("STARTING_CSV", openShiftConfiguration.getInstalledCSV());
        } else {
            subscriptionYaml = subscriptionYaml.replaceAll("\\s*\\w*:\\s*STARTING_CSV", "");
        }

        CustomResourceDefinitionContext subscriptionCrdContext = new CustomResourceDefinitionContext.Builder()
                .withGroup("operators.coreos.com")
                .withPlural("subscriptions")
                .withScope("Namespaced")
                .withVersion("v1alpha1")
                .build();

        openShiftClient.customResource(subscriptionCrdContext)
                .createOrReplace(openShiftConfiguration.getNamespace(),
                        new ByteArrayInputStream(subscriptionYaml.getBytes(StandardCharsets.UTF_8)));

        //OpenshiftRole role = openShiftClient.roles().inNamespace(openShiftConfiguration.getNamespace()).withLabel("olm.owner", "fuse-online" +
                //"-operator.v7.7.0").list().getItems().get(0);
//        openShiftClient.serviceAccounts().inNamespace(openShiftConfiguration.getNamespace())
//                .list().getItems().stream().filter(sa -> sa.getMetadata().getName().contains("operator"))
//                .forEach(sa -> {
//                    openShiftClient.addRoleToServiceAccount(role.getMetadata().getName(), sa.getMetadata().getName());
//                });

        try {
            waitFor(() ->
                            openShiftClient.inNamespace(openShiftConfiguration.getNamespace()).pods().list().getItems().size() == 1,
                    1, 2 * 60);
        } catch (InterruptedException | TimeoutException e) {
            log.error("There is no pod in project after waiting for 120 seconds");
            throw new IOException("Pod has not been created and/or stared", e);
        }
    }

    private void scaleOperatorPod(int scale, String operatorResourcesName) throws IOException {
        openShiftClient
                .apps().deployments().inNamespace(openShiftConfiguration.getNamespace())
                .withName(operatorResourcesName).scale(scale);
        try {
            HelperFunctions.waitFor(
                    () -> openShiftClient.pods().inNamespace(openShiftConfiguration.getNamespace())
                            .list().getItems().size() == scale,
                    1, 30
            );
        } catch (InterruptedException | TimeoutException e) {
            log.error("Couldn't wait for pod to scale");
            throw new IOException("Operator pod did not scale", e);
        }
    }

    private void linkPullSecret(String operatorResourcesName) {
        log.info("Linking pull secret to service account user");

        HelperFunctions.linkPullSecret(
                openShiftClient,
                openShiftConfiguration.getNamespace(),
                operatorResourcesName,
                openShiftConfiguration.getPullSecretName());
    }
}
