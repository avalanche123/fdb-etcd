package fr.pierrezemb.fdb.layer.etcd.service;

import com.google.common.base.Charsets;
import fr.pierrezemb.fdb.layer.etcd.AbstractFDBContainer;
import fr.pierrezemb.fdb.layer.etcd.MainVerticle;
import fr.pierrezemb.fdb.layer.etcd.PortManager;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.lease.LeaseTimeToLiveResponse;
import io.etcd.jetcd.options.LeaseOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.support.Observers;
import io.grpc.stub.StreamObserver;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Taken from <a href="https://github.com/etcd-io/jetcd/blob/master/jetcd-core/src/test/java/io/etcd/jetcd/LeaseTest.java">...</a>
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LeaseServiceTest extends AbstractFDBContainer {
  private static final ByteSequence KEY = ByteSequence.from("foo", Charsets.UTF_8);
  private static final ByteSequence KEY_2 = ByteSequence.from("foo2", Charsets.UTF_8);
  private static final ByteSequence KEY_3 = ByteSequence.from("foo3", Charsets.UTF_8);
  private static final ByteSequence VALUE = ByteSequence.from("bar", Charsets.UTF_8);
  public final int port = PortManager.nextFreePort();
  private KV kvClient;
  private Client client;
  private Lease leaseClient;

  @BeforeAll
  void deploy_verticle(Vertx vertx, VertxTestContext testContext) {

    File clusterFile = container.clearAndGetClusterFile();

    DeploymentOptions options = new DeploymentOptions()
      .setConfig(new JsonObject().put("fdb-cluster-file", clusterFile.getAbsolutePath()).put("listen-port", port)
      );

    // deploy verticle
    vertx.deployVerticle(new MainVerticle(), options, testContext.succeeding(id -> {
      // create client
      client = Client.builder().endpoints("http://localhost:" + port).build();
      // uncomment this to test on real etcd
      // client = Client.builder().endpoints("http://localhost:2379").build();
      kvClient = client.getKVClient();
      leaseClient = client.getLeaseClient();

      testContext.completeNow();
    }));

  }

  @Test
  public void testGrant() throws Exception {
    // ttl is 5s
    long leaseID = leaseClient.grant(5).get().getID();

    kvClient.put(KEY, VALUE, PutOption.builder().withLeaseId(leaseID).build()).get();
    Assertions.assertEquals(kvClient.get(KEY).get().getCount(), 1);

    // let's wait 6s
    Thread.sleep(6000);
    Assertions.assertEquals(kvClient.get(KEY).get().getCount(), 0);
  }

  @Test
  public void testRevoke() throws Exception {
    long leaseID = leaseClient.grant(5).get().getID();
    kvClient.put(KEY_2, VALUE, PutOption.builder().withLeaseId(leaseID).build()).get();
    Assertions.assertEquals(1, kvClient.get(KEY_2).get().getCount());
    leaseClient.revoke(leaseID).get();
    Assertions.assertEquals(0, kvClient.get(KEY_2).get().getCount());
  }

  @Test
  public void testKeepAliveOnce() throws InterruptedException, ExecutionException {
    long leaseID = leaseClient.grant(2).get().getID();
    kvClient.put(KEY, VALUE, PutOption.builder().withLeaseId(leaseID).build()).get();
    Assertions.assertEquals(1, kvClient.get(KEY).get().getCount());
    LeaseKeepAliveResponse rp = leaseClient.keepAliveOnce(leaseID).get();
    Assertions.assertTrue(rp.getTTL() > 0);
  }

  @Test
  public void testKeepAlive() throws ExecutionException, InterruptedException {
    long leaseID = leaseClient.grant(2).get().getID();
    kvClient.put(KEY, VALUE, PutOption.builder().withLeaseId(leaseID).build()).get();
    Assertions.assertEquals(1, kvClient.get(KEY).get().getCount());

    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<LeaseKeepAliveResponse> responseRef = new AtomicReference<>();
    StreamObserver<LeaseKeepAliveResponse> observer = Observers.observer(response -> {
      responseRef.set(response);
      latch.countDown();
    });

    try (CloseableClient ignored = leaseClient.keepAlive(leaseID, observer)) {
      Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
      LeaseKeepAliveResponse response = responseRef.get();
      Assertions.assertTrue(response.getTTL() > 0);
    }

    Thread.sleep(3000);
    Assertions.assertEquals(0, kvClient.get(KEY).get().getCount());
  }

  @Test
  public void testTimeToLive() throws ExecutionException, InterruptedException {
    long ttl = 5;
    long leaseID = leaseClient.grant(ttl).get().getID();
    LeaseTimeToLiveResponse resp = leaseClient.timeToLive(leaseID, LeaseOption.DEFAULT).get();
    Assertions.assertTrue(resp.getGrantedTTL() > 0);
    Assertions.assertEquals(resp.getGrantedTTL(), ttl);
  }

  @Test
  public void testTimeToLiveWithKeys() throws ExecutionException, InterruptedException {
    long ttl = 5;
    long leaseID = leaseClient.grant(ttl).get().getID();
    PutOption putOption = PutOption.builder().withLeaseId(leaseID).build();
    kvClient.put(KEY_3, VALUE, putOption).get();

    LeaseOption leaseOption = LeaseOption.builder().withAttachedKeys().build();
    LeaseTimeToLiveResponse resp = leaseClient.timeToLive(leaseID, leaseOption).get();
    Assertions.assertTrue(resp.getTTL() > 0);
    Assertions.assertTrue(resp.getGrantedTTL() > 0);
    Assertions.assertEquals(resp.getKeys().size(), 1);
    Assertions.assertEquals(resp.getKeys().get(0).toString(Charset.defaultCharset()), KEY_3.toString(Charset.defaultCharset()));
  }
}
