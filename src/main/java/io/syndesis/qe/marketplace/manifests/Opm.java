package io.syndesis.qe.marketplace.manifests;

import io.syndesis.qe.marketplace.quay.QuayUser;
import io.syndesis.qe.marketplace.util.HelperFunctions;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Opm {

    private File binary;
    private static final String OPM_IMAGE = "registry.redhat.io/openshift4/ose-operator-registry:";
    private static final String version = "v4.5";
    
    public Opm() {
        try {
            Process which = new ProcessBuilder("which", "opm").start();
            int result = which.waitFor();
            String path = IOUtils.toString(which.getInputStream(), Charset.defaultCharset()).trim();
            if (result != 0) {
                fetchOpm(version);
            } else {
                binary = new File(path);
            }
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Opm(String version) {
        fetchOpm(version);
    }

    private void fetchOpm(String version) {
        try {
            binary = File.createTempFile("opm", version);
            Process p = new ProcessBuilder("docker", "run", "--rm", "--entrypoint=/usr/bin/cat", OPM_IMAGE + version, "/usr/bin/opm")
                .redirectOutput(binary)
                .start();
            p.waitFor();
            if (p.exitValue() != 0) {
                throw new RuntimeException(IOUtils.toString(p.getErrorStream(), Charset.defaultCharset()));
            }
            binary.setExecutable(true);
            binary.deleteOnExit();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    void runOpmCmd(String... args) {
        String[] command = new String[args.length + 1];
        System.arraycopy(args, 0, command, 1, args.length);
        command[0] = binary.getAbsolutePath();
        HelperFunctions.runCmd(command);
    }

    public Index createIndex(String name) {
        return new Index(name, this);
    }

    public Index pullIndex(String name, QuayUser user) {
        Index index = new Index(name, this);
        index.pull(user);
        return index;
    }
}
