package io.syndesis.qe.marketplace.openshift;

public class OpenShiftUser {

    private String userName;
    private String password;

    private String apiUrl;

    public OpenShiftUser(String userName, String password, String apiUrl) {
        this.userName = userName;
        this.password = password;
        this.apiUrl = apiUrl;
    }

    public String getUserName() {
        return userName;
    }

    public String getPassword() {
        return password;
    }

    public String getApiUrl() {
        return apiUrl;
    }
}
