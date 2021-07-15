package io.syndesis.qe.marketplace.openshift;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class OpenShiftConfiguration {
    private String namespace;
    private String icspFile;
    private String dockerRegistry;
    private String dockerUsername;
    private String dockerPassword;
}
