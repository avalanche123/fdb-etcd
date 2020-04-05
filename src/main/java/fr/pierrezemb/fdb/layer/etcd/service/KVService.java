package fr.pierrezemb.fdb.layer.etcd.service;


import com.google.protobuf.InvalidProtocolBufferException;
import etcdserverpb.EtcdIoRpcProto;
import etcdserverpb.KVGrpc;
import fr.pierrezemb.etcd.record.pb.EtcdRecord;
import fr.pierrezemb.fdb.layer.etcd.store.EtcdRecordStore;
import fr.pierrezemb.fdb.layer.etcd.utils.ProtoUtils;
import io.vertx.core.Promise;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mvccpb.EtcdIoKvProto;

public class KVService extends KVGrpc.KVVertxImplBase {

  private EtcdRecordStore recordStore;

  public KVService(EtcdRecordStore recordStore) {
    this.recordStore = recordStore;
  }

  /**
   * <pre>
   * Put puts the given key into the key-value store.
   * A put request increments the revision of the key-value store
   * and generates one event in the event history.
   * </pre>
   *
   * @param request
   * @param response
   */
  @Override
  public void put(EtcdIoRpcProto.PutRequest request, Promise<EtcdIoRpcProto.PutResponse> response) {
    EtcdRecord.KeyValue put;
    try {
      put = this.recordStore.put(ProtoUtils.from(request));
    } catch (InvalidProtocolBufferException e) {
      response.fail(e);
      return;
    }
    response.complete(
      EtcdIoRpcProto
        .PutResponse.newBuilder()
        .setHeader(
          EtcdIoRpcProto.ResponseHeader.newBuilder().setRevision(put.getModRevision()).build()
        ).build()
    );
  }

  /**
   * <pre>
   *   range retrieves keys.
   * 	 By default, Get will return the value for "key", if any.
   * 	 When passed WithRange(end), Get will return the keys in the range [key, end).
   * 	 When passed WithFromKey(), Get returns keys greater than or equal to key.
   * 	 When passed WithRev(rev) with rev > 0, Get retrieves keys at the given revision;
   * 	 if the required revision is compacted, the request will fail with ErrCompacted .
   * 	 When passed WithLimit(limit), the number of returned keys is bounded by limit.
   * 	 When passed WithSort(), the keys will be sorted.
   * </pre>
   *
   * @param request
   * @param response
   */
  @Override
  public void range(EtcdIoRpcProto.RangeRequest request, Promise<EtcdIoRpcProto.RangeResponse> response) {

    List<EtcdRecord.KeyValue> results = new ArrayList<>();
    int version = Math.toIntExact(request.getRevision());

    if (request.getRangeEnd().isEmpty()) {
      // get
      results.add(this.recordStore.get(request.getKey().toByteArray(), version));
    } else {
      // scan
      results = this.recordStore.scan(request.getKey().toByteArray(), request.getRangeEnd().toByteArray(), version);
    }

    List<EtcdIoKvProto.KeyValue> kvs = results.stream()
      .flatMap(Stream::ofNullable)
      .map(e -> EtcdIoKvProto.KeyValue.newBuilder()
        .setKey(e.getKey()).setValue(e.getValue()).build()).collect(Collectors.toList());

    if (request.getSortOrder().getNumber() > 0) {
      kvs.sort(createComparatorFromRequest(request));
    }

    response.complete(EtcdIoRpcProto.RangeResponse.newBuilder().addAllKvs(kvs).setCount(kvs.size()).build());
  }

  private Comparator<? super EtcdIoKvProto.KeyValue> createComparatorFromRequest(EtcdIoRpcProto.RangeRequest request) {
    Comparator<EtcdIoKvProto.KeyValue> comparator;
    switch (request.getSortTarget()) {
      case KEY:
        comparator = Comparator.comparing(e -> e.getKey().toStringUtf8());
        break;
      case VERSION:
        comparator = Comparator.comparing(EtcdIoKvProto.KeyValue::getVersion);
        break;
      case CREATE:
        comparator = Comparator.comparing(EtcdIoKvProto.KeyValue::getCreateRevision);
        break;
      case MOD:
        comparator = Comparator.comparing(EtcdIoKvProto.KeyValue::getModRevision);
        break;
      case VALUE:
        comparator = Comparator.comparing(e -> e.getValue().toStringUtf8());
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + request.getSortTarget());
    }

    if (request.getSortOrder().equals(EtcdIoRpcProto.RangeRequest.SortOrder.DESCEND)) {
      comparator = comparator.reversed();
    }

    return comparator;
  }


  /**
   * <pre>
   * DeleteRange deletes the given range from the key-value store.
   * A delete request increments the revision of the key-value store
   * and generates a delete event in the event history for every deleted key.
   * </pre>
   *
   * @param request
   * @param response
   */
  @Override
  public void deleteRange(EtcdIoRpcProto.DeleteRangeRequest request, Promise<EtcdIoRpcProto.DeleteRangeResponse> response) {
    Integer count = this.recordStore.delete(
      request.getKey().toByteArray(),
      request.getRangeEnd().isEmpty() ? request.getKey().toByteArray() : request.getRangeEnd().toByteArray());
    response.complete(EtcdIoRpcProto.DeleteRangeResponse.newBuilder().setDeleted(count.longValue()).build());
  }

  /**
   * <pre>
   * Compact compacts the event history in the etcd key-value store. The key-value
   * store should be periodically compacted or the event history will continue to grow
   * indefinitely.
   * </pre>
   *
   * @param request
   * @param response
   */
  @Override
  public void compact(EtcdIoRpcProto.CompactionRequest request, Promise<EtcdIoRpcProto.CompactionResponse> response) {
    this.recordStore.compact(request.getRevision());
    response.complete();
  }
}
