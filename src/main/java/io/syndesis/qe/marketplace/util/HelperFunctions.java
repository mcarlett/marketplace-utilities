package io.syndesis.qe.marketplace.util;

import static io.syndesis.qe.marketplace.manifests.Index.CONTAINER_TOOL;

import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HelperFunctions {

    public static String readResource(String resourceName) throws IOException {
        StringBuilder sb = new StringBuilder("");
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceName);
        try (InputStreamReader reader = new InputStreamReader(is)) {
            BufferedReader bf = new BufferedReader(reader);

            String line;
            while ((line = bf.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    public static boolean waitFor(BooleanSupplier condition, long interval, long timeout)
        throws InterruptedException, TimeoutException {

        long intervalInMilis = interval * 1000;
        long timeoutInMilis = timeout * 1000;

        long waitUntil = System.currentTimeMillis() + timeoutInMilis;

        while (System.currentTimeMillis() < waitUntil) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.sleep(intervalInMilis);
        }

        throw new TimeoutException();
    }

    public static void runCmd(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            log.info("Running command {}", Arrays.asList(command));

            int ret = 0;
            ret = process.waitFor();
            log.info("Command finished with return code {}", ret);
            String out = IOUtils.toString(process.getErrorStream(), Charset.defaultCharset()) + "\n" +
                IOUtils.toString(process.getInputStream(), Charset.defaultCharset());
            log.debug(out);
            if (ret != 0) {
                throw new RuntimeException(
                    String.format("Command finished with non-zero value!, Command: '%s', Output: '%s'", Arrays.toString(command), out));
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void containerToolCmd(String action, String... args) {
        final String dockerCfg = System.getProperty("DOCKER_CONFIG");
        if (dockerCfg != null) {
            if (CONTAINER_TOOL.equalsIgnoreCase("docker")) {
                final List<String> cmdList = new ArrayList<>(Arrays.asList(CONTAINER_TOOL, "--config", dockerCfg, action));
                cmdList.addAll(Arrays.asList(args));
                final String[] cmd = cmdList.toArray(new String[0]);
                HelperFunctions.runCmd(cmd);
            } else {
                final List<String> cmdList = new ArrayList<>(Arrays.asList(CONTAINER_TOOL, action));
                cmdList.addAll(Arrays.asList(args));
                cmdList.add("--authfile");
                cmdList.add(dockerCfg + "/config.json");
                String[] cmd = cmdList.toArray(new String[0]);
                HelperFunctions.runCmd(cmd);
            }
        } else {
            final List<String> cmdList = new ArrayList<>(Arrays.asList(CONTAINER_TOOL, action));
            cmdList.addAll(Arrays.asList(args));
            HelperFunctions.runCmd(cmdList.toArray(new String[0]));
        }
    }
}
