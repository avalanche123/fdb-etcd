package fr.pierrezemb.fdb.layer.etcd;

import com.apple.foundationdb.Database;
import com.apple.foundationdb.FDB;
import com.apple.foundationdb.FDBException;
import com.apple.foundationdb.Range;
import com.apple.foundationdb.tuple.Tuple;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.Base58;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;

public class FoundationDBContainer extends GenericContainer<FoundationDBContainer> {
    private static final Logger log = LoggerFactory.getLogger(FoundationDBContainer.class);
    private static final DockerImageName FDB_IMAGE = DockerImageName.parse("foundationdb/foundationdb");
    public static final int FDB_PORT = 4500;
    private static final String FDB_VERSION = "7.1.35";
    private static final int FDB_API_VERSION = 630;
    private final int fdbApiVersion;
    private File clusterFile;

    private GenericContainer<?> proxy;

    public FoundationDBContainer() {
        this(FDB_VERSION, FDB_API_VERSION);
    }

    public FoundationDBContainer(String fdbVersion, int fdbApiVersion) {
        super(FDB_IMAGE.withTag(fdbVersion));
        this.fdbApiVersion = fdbApiVersion;
    }

    @Override
    protected void configure() {
        // FDB server port and the port seen by FDB clients must match, otherwise the FDB server will crash with
        // errors like "Assertion pkt.canonicalRemotePort == peerAddress.port failed..."
        proxy = new GenericContainer<>(DockerImageName.parse("alpine/socat:1.7.4.3-r0"))
                .withCreateContainerCmdModifier(it -> it.withEntrypoint("/bin/sh"))
                .withCreateContainerCmdModifier(it -> it.withName("testcontainers-socat-" + Base58.randomString(8)))
                .withNetwork(Network.newNetwork())
                // the real port we want to proxy to FDB, will get the binding port after socat proxy container is starting
                .withExposedPorts(FDB_PORT)
                .withCommand("-c", "--", "trap : TERM INT; sleep infinity & wait")
                .withReuse(isShouldBeReused());
        proxy.setWaitStrategy(null);
        proxy.start();
        withNetwork(proxy.getNetwork());
        withEnv("FDB_PORT", String.valueOf(proxy.getMappedPort(FDB_PORT)));
        withEnv("FDB_COORDINATOR_PORT", String.valueOf(proxy.getMappedPort(FDB_PORT)));
        withEnv("FDB_NETWORKING_MODE", "host");
        waitingFor(Wait.forLogMessage(".*FDBD joined cluster.*\\n", 1));
    }

    private void proxyBindPort() throws InterruptedException, IOException {
        Container.ExecResult bindResult = proxy.execInContainer("/bin/sh", "-c", "socat TCP-LISTEN:" + FDB_PORT + ",fork,reuseaddr TCP:" + getNetworkAliases().get(0) + ":" + proxy.getMappedPort(FDB_PORT) + " &");
        final String stdout = bindResult.getStdout();
        if (!stdout.isEmpty()) {
            log.debug("socat stdout: {}", stdout);
        }
        final String stderr = bindResult.getStderr();
        if (!stderr.isEmpty()) {
            log.error("socat stderr: {}", stderr);
        }
        int exitCode = bindResult.getExitCode();
        log.debug("socat exit code: {}", exitCode);
        if (exitCode != 0) {
            throw new RuntimeException(String.format("Error when attempting to bind port with socat: %s", stderr));
        }
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo) {
        try {
            Container.ExecResult initResult = execInContainer("fdbcli", "--exec", "configure new single memory");
            String stdout = initResult.getStdout();
            log.debug("init FDB stdout: " + stdout);
            int exitCode = initResult.getExitCode();
            log.debug("init FDB exit code: " + exitCode);

            log.info("waiting for FDB to be healthy");
            // waiting for fdb to be up and healthy
            while (!execInContainer("fdbcli", "--exec", "status").getStdout().contains("Healthy")) {
                log.debug("fdb is unhealthy");
                Thread.sleep(10 * 1000);
            }
            proxyBindPort();
            clusterFile = File.createTempFile("fdb", ".cluster");
            copyFileFromContainer("/var/fdb/fdb.cluster", clusterFile.getAbsolutePath());
            log.info("fdb is healthy, clusterFile is at {}", clusterFile.getAbsolutePath());
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A hook that is executed after the container is stopped with {@link #stop()}.
     * Warning! This hook won't be executed if the container is terminated during
     * the JVM's shutdown hook or by Ryuk.
     *
     * @param containerInfo
     */
    @Override
    protected void containerIsStopped(InspectContainerResponse containerInfo) {
        super.containerIsStopped(containerInfo);
        clusterFile.delete();
        proxy.stop();
    }

    public File clearAndGetClusterFile() {
        clearFDB();
        return clusterFile;
    }

    public void clearFDB() throws FDBException {
        FDB fdb = FDB.selectAPIVersion(fdbApiVersion);

        try (Database db = fdb.open(clusterFile.getAbsolutePath())) {
            log.debug("clearing FDB...");
            db.run(transaction -> {
                transaction.clear(Range.startsWith(Tuple.from("").pack()));
                return null;
            });
            log.debug("clearing FDB done");
        }
    }

}
