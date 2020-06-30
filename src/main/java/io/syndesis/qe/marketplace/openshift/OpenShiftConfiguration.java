package io.syndesis.qe.marketplace.openshift;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class OpenShiftConfiguration {

    private String namespace;
    private String pullSecretName;
    private String pullSecret;
    private String quayOpsrcToken;
    private String installedCSV;

}
