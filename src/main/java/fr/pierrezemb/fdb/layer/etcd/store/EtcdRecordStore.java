package fr.pierrezemb.fdb.layer.etcd.store;

import com.apple.foundationdb.record.EndpointType;
import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataBuilder;
import com.apple.foundationdb.record.ScanProperties;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.provider.foundationdb.FDBDatabase;
import com.apple.foundationdb.record.provider.foundationdb.FDBDatabaseFactory;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStore;
import com.apple.foundationdb.record.provider.foundationdb.FDBStoredRecord;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.KeySpace;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.KeySpaceDirectory;
import com.apple.foundationdb.record.provider.foundationdb.keyspace.KeySpacePath;
import com.apple.foundationdb.tuple.Tuple;
import com.google.protobuf.Message;
import fr.pierrezemb.etcd.record.pb.EtcdRecord;
import java.util.List;
import java.util.function.Function;

public class EtcdRecordStore {
  public final FDBDatabase db;
  private final KeySpace keySpace;
  private final KeySpacePath path;
  public final Function<FDBRecordContext, FDBRecordStore> recordStoreProvider;

  public EtcdRecordStore(String clusterFilePath) {

    // get DB
    db = FDBDatabaseFactory.instance().getDatabase(clusterFilePath);

    // Define the keyspace for our application
    keySpace = new KeySpace(new KeySpaceDirectory("record-layer-demo", KeySpaceDirectory.KeyType.STRING, "record-layer-demo"));

    // Get the path where our record store will be rooted
    path = keySpace.path("record-layer-demo");

    RecordMetaDataBuilder metaDataBuilder = RecordMetaData.newBuilder()
      .setRecords(EtcdRecord.getDescriptor());

    metaDataBuilder.getRecordType("PutRequest").setPrimaryKey(Key.Expressions.field("key"));

    recordStoreProvider = context -> FDBRecordStore.newBuilder()
      .setMetaDataProvider(metaDataBuilder)
      .setContext(context)
      .setKeySpacePath(path)
      .createOrOpen();
  }

  public EtcdRecord.PutRequest get(Tuple key) {
    return db.run(context -> {
      FDBStoredRecord<Message> storedMessage = recordStoreProvider.apply(context).loadRecord(key);
      if (storedMessage == null) {
        return null;
      }
      return EtcdRecord.PutRequest.newBuilder()
        .mergeFrom(storedMessage.getRecord())
        .build();
    });
  }

  public List<EtcdRecord.PutRequest> scan(Tuple start, Tuple end) {
    return db.run(context -> {
      FDBRecordStore recordStore = recordStoreProvider.apply(context);

      // this returns an asynchronous cursor over the results of our query
      return recordStore.scanRecords(
        start, end,
        EndpointType.RANGE_INCLUSIVE, EndpointType.RANGE_INCLUSIVE,
        null, ScanProperties.FORWARD_SCAN)
        .map(queriedRecord -> EtcdRecord.PutRequest.newBuilder()
          .mergeFrom(queriedRecord.getRecord()).build())
        .asList().join();
    });
  }

  public void put(EtcdRecord.PutRequest request) {
    this.db.run(context -> {
      FDBRecordStore recordStore = recordStoreProvider.apply(context);
      return recordStore.saveRecord(request);
    });
  }
}
