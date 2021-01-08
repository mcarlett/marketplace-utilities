package io.syndesis.qe.marketplace.quay;

import io.syndesis.qe.marketplace.tar.Compress;
import io.syndesis.qe.marketplace.util.HelperFunctions;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

@Slf4j
public class QuayService {

    private final String operatorImage;
    private final QuayUser quayUser;

    private final String QUAY_LOGIN_URL;
    private final String QUAY_PUSH_URL;
    private final String QUAY_REPOSITORY_URL;
    private final String QUAY_CHANGE_VISIBILITY_URL;

    @Getter
    private String installedCSV;

    @Getter
    private String packageName;

    private final Map<String, String> envVars;


    public QuayService(QuayUser quayUser, String operatorImage, Map<String, String> envVars) {
        this.operatorImage = operatorImage;
        this.quayUser = quayUser;
        this.envVars = envVars;

        QUAY_LOGIN_URL = "https://quay.io/cnr/api/v1/users/login";
        QUAY_PUSH_URL = "https://quay.io/cnr/api/v1/packages/" +
                quayUser.getNamespace() + "/QUAY_PACKAGE";
        QUAY_REPOSITORY_URL = "https://quay.io/api/v1/repository/" +
                quayUser.getNamespace() + "/QUAY_PACKAGE";
        QUAY_CHANGE_VISIBILITY_URL = QUAY_REPOSITORY_URL + "/changevisibility";
    }

    public String createQuayProject() throws IOException {
        String operatorName = HelperFunctions.getOperatorName(operatorImage);
        Path tempDir = Files.createTempDirectory(operatorName);

        log.info("Cleaning temoporary directory " + tempDir.toString());

        FileUtils.cleanDirectory(tempDir.toFile());

        log.info("Acquiring manifests from operator image");

        String result = HelperFunctions.copyManifestFilestFromImage(operatorImage, tempDir.toString());
        if (StringUtils.isNotEmpty(result)) {
            log.error("Could not get files from image, result from docker command is:");
            log.error(result);
            throw new RuntimeException();
        }

        packageName = HelperFunctions.getPackageName(tempDir.resolve("manifests"));
        String operatorVersion = HelperFunctions.getOperatorVersion(tempDir.resolve("manifests"));
        String tarFile = tempDir.toString() + "/" + packageName + ".tar.gz";

        log.info("Operator application name is '" + packageName + "'");
        log.info("Operator version is '" + operatorVersion + "'");

        String base64 = compressAndGetBase64(tempDir, operatorVersion, operatorName, tarFile);

        String token = loginToQuayAndGetToken();
        createApplicationOnQuay(base64, operatorVersion, packageName, token);
        changeProjectVisibilityToPublic(packageName);

        return packageName;
    }

    public void deleteQuayProject() throws IOException {
        log.info("Deleting project from quay");
        String botToken = "Bearer " + quayUser.getToken();
        HelperFunctions.doDeleteRequest(
                QUAY_REPOSITORY_URL.replaceAll("QUAY_PACKAGE", packageName),
                null,
                botToken
        );
    }

    private Path findClusterServiceFile(Path tempDir, String operatorVersion, String operatorName) {
        Path dir = tempDir.resolve("manifests").resolve(operatorVersion);
        String[] matches = dir.toFile().list((f, s) -> s.endsWith(operatorVersion + ".clusterserviceversion.yaml"));
        if (matches.length > 1){
            return dir.resolve(Arrays.stream(matches).filter(s -> s.contains("operator")).findFirst().get());
        }
        return dir.resolve(matches[0]);
    }

    private String compressAndGetBase64(Path tempDir,
                                        String operatorVersion,
                                        String operatorName,
                                        String tarFile) throws IOException {

        Path fileToFix = tempDir.resolve("manifests").resolve(operatorVersion)
                .resolve(operatorName + ".v" + operatorVersion + ".clusterserviceversion.yaml");
        if (Files.notExists(fileToFix)) {
            fileToFix = findClusterServiceFile(tempDir, operatorVersion, operatorName);
        }
        log.info("Fixing operator image in manifest file " + fileToFix.toString());

        installedCSV = fileToFix.getFileName().toString().replace(".clusterserviceversion.yaml", "");

        HelperFunctions.replaceImageInManifest(fileToFix, operatorImage, envVars);

        String random = RandomStringUtils.random(8, true, true).toLowerCase();

        Compress compress = new Compress(tarFile, packageName + "-" + random);
        compress.writedir(tempDir.resolve("manifests"));
        compress.close();

        return HelperFunctions.encodeFileToBase64Binary(tarFile);
    }

    public String loginToQuayAndGetToken() throws IOException {
        log.info("Acquiring quay login data");

        String quayLoginRequest = HelperFunctions.readResource("quay/quay-login.json")
                .replaceAll("QUAY_USERNAME", quayUser.getUserName())
                .replaceAll("QUAY_PASSWORD", quayUser.getPassword());

        String quayLoginResponse = HelperFunctions.doPostRequest(QUAY_LOGIN_URL, quayLoginRequest, null);

        JSONObject jsonObject = new JSONObject(quayLoginResponse);
        return jsonObject.getString("token");
    }

    private void createApplicationOnQuay(String packagePayload, String operatorVersion, String packageName, String token) throws IOException {
        log.info("Creating application on quay");

        String pushBody = HelperFunctions.readResource("quay/quay-push.json")
                .replaceAll("QUAY_PAYLOAD", packagePayload)
                .replaceAll("QUAY_RELEASE", operatorVersion);

        HelperFunctions.doPostRequest(
                QUAY_PUSH_URL.replaceAll("QUAY_PACKAGE", packageName),
                pushBody,
                token
        );
    }

    public void changeProjectVisibilityToPublic(String packageName) throws IOException {
        log.info("Changing quay application visibility to 'public'");

        String pushBody = "{\"visibility\":\"public\"}";
        String botToken = "Bearer " + quayUser.getToken();
        log.info(
                HelperFunctions.doPostRequest(QUAY_CHANGE_VISIBILITY_URL.replaceAll("QUAY_PACKAGE", packageName), pushBody, botToken)
        );
    }

}
