package io.syndesis.qe.marketplace.quay;

public class QuayUser {

    private String userName;
    private String password;
    private String namespace;

    private String token;

    public QuayUser(String userName, String password, String namespace, String token) {
        this.userName = userName;
        this.password = password;
        this.namespace = namespace;
        this.token = token;
    }

    public String getUserName() {
        return userName;
    }

    public String getPassword() {
        return password;
    }

    public String getToken() {
        return token;
    }

    public String getNamespace() {
        return namespace;
    }
}
