/*
 *
 *  *  Copyright 2010-2016 OrientDB LTD (http://orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.client.remote.message;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.orientechnologies.common.collection.OMultiValue;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.client.binary.OChannelBinaryAsynchClient;
import com.orientechnologies.orient.client.remote.OBinaryResponse;
import com.orientechnologies.orient.client.remote.OFetchPlanResults;
import com.orientechnologies.orient.client.remote.ORemoteConnectionPool;
import com.orientechnologies.orient.client.remote.OStorageRemote;
import com.orientechnologies.orient.client.remote.OStorageRemoteSession;
import com.orientechnologies.orient.client.remote.SimpleValueFetchPlanCommandListener;
import com.orientechnologies.orient.core.command.OCommandRequestText;
import com.orientechnologies.orient.core.command.OCommandResultListener;
import com.orientechnologies.orient.core.db.ODatabaseDocumentInternal;
import com.orientechnologies.orient.core.db.ODatabaseRecordThreadLocal;
import com.orientechnologies.orient.core.db.document.ODatabaseDocument;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.ORecordOperation;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.id.ORecordId;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.ORecord;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.serializer.record.string.ORecordSerializerStringAbstract;
import com.orientechnologies.orient.core.sql.query.OBasicResultSet;
import com.orientechnologies.orient.core.sql.query.OLiveResultListener;
import com.orientechnologies.orient.core.type.ODocumentWrapper;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinary;
import com.orientechnologies.orient.enterprise.channel.binary.OChannelBinaryProtocol;

public final class OCommandResponse implements OBinaryResponse {
  private boolean                   asynch;
  private OCommandResultListener    listener;
  private ODatabaseDocumentInternal database;
  private boolean                   live;
  private Object                    result;
  private boolean                   isRecordResultSet;
  private OCommandRequestText       command;
  private Map<Object, Object>       params;

  public OCommandResponse(Object result, SimpleValueFetchPlanCommandListener listener, boolean isRecordResultSet, boolean async,
      ODatabaseDocumentInternal database, OCommandRequestText command, Map<Object, Object> params) {
    this.result = result;
    this.listener = listener;
    this.isRecordResultSet = isRecordResultSet;
    this.asynch = async;
    this.database = database;
    this.command = command;
    this.params = params;
  }

  public OCommandResponse(boolean asynch, OCommandResultListener listener, ODatabaseDocumentInternal database, boolean live) {
    this.asynch = asynch;
    this.listener = listener;
    this.database = database;
    this.live = live;
  }

  public void write(OChannelBinary channel, int protocolVersion, String recordSerializer) throws IOException {
    if (asynch) {
      if (params == null)
        result = database.command(command).execute();
      else
        result = database.command(command).execute(params);

      // FETCHPLAN HAS TO BE ASSIGNED AGAIN, because it can be changed by SQL statement
      channel.writeByte((byte) 0); // NO MORE RECORDS
    } else {
      serializeValue(channel, (SimpleValueFetchPlanCommandListener) listener, result, false, isRecordResultSet, protocolVersion,
          recordSerializer);
      if (listener instanceof OFetchPlanResults) {
        // SEND FETCHED RECORDS TO LOAD IN CLIENT CACHE
        for (ORecord rec : ((OFetchPlanResults) listener).getFetchedRecordsToSend()) {
          channel.writeByte((byte) 2); // CLIENT CACHE RECORD. IT
          // ISN'T PART OF THE
          // RESULT SET
          OBinaryProtocolHelper.writeIdentifiable(channel, rec, recordSerializer);
        }

        channel.writeByte((byte) 0); // NO MORE RECORDS
      }
    }

  }

  public void serializeValue(OChannelBinary channel, final SimpleValueFetchPlanCommandListener listener, Object result,
      boolean load, boolean isRecordResultSet, int protocolVersion, String recordSerializer) throws IOException {
    if (result == null) {
      // NULL VALUE
      channel.writeByte((byte) 'n');
    } else if (result instanceof OIdentifiable) {
      // RECORD
      channel.writeByte((byte) 'r');
      if (load && result instanceof ORecordId)
        result = ((ORecordId) result).getRecord();

      if (listener != null)
        listener.result(result);
      OBinaryProtocolHelper.writeIdentifiable(channel, (OIdentifiable) result, recordSerializer);
    } else if (result instanceof ODocumentWrapper) {
      // RECORD
      channel.writeByte((byte) 'r');
      final ODocument doc = ((ODocumentWrapper) result).getDocument();
      if (listener != null)
        listener.result(doc);
      OBinaryProtocolHelper.writeIdentifiable(channel, doc, recordSerializer);
    } else if (!isRecordResultSet) {
      writeSimpleValue(channel, listener, result, protocolVersion, recordSerializer);
    } else if (OMultiValue.isMultiValue(result)) {
      final byte collectionType = result instanceof Set ? (byte) 's' : (byte) 'l';
      channel.writeByte(collectionType);
      channel.writeInt(OMultiValue.getSize(result));
      for (Object o : OMultiValue.getMultiValueIterable(result, false)) {
        try {
          if (load && o instanceof ORecordId)
            o = ((ORecordId) o).getRecord();
          if (listener != null)
            listener.result(o);

          OBinaryProtocolHelper.writeIdentifiable(channel, (OIdentifiable) o, recordSerializer);
        } catch (Exception e) {
          OLogManager.instance().warn(this, "Cannot serialize record: " + o);
          OBinaryProtocolHelper.writeIdentifiable(channel, null, recordSerializer);
          // WRITE NULL RECORD TO AVOID BREAKING PROTOCOL
        }
      }
    } else if (OMultiValue.isIterable(result)) {
      if (protocolVersion >= OChannelBinaryProtocol.PROTOCOL_VERSION_32) {
        channel.writeByte((byte) 'i');
        for (Object o : OMultiValue.getMultiValueIterable(result)) {
          try {
            if (load && o instanceof ORecordId)
              o = ((ORecordId) o).getRecord();
            if (listener != null)
              listener.result(o);

            channel.writeByte((byte) 1); // ONE MORE RECORD
            OBinaryProtocolHelper.writeIdentifiable(channel, (OIdentifiable) o, recordSerializer);
          } catch (Exception e) {
            OLogManager.instance().warn(this, "Cannot serialize record: " + o);
          }
        }
        channel.writeByte((byte) 0); // NO MORE RECORD
      } else {
        // OLD RELEASES: TRANSFORM IN A COLLECTION
        final byte collectionType = result instanceof Set ? (byte) 's' : (byte) 'l';
        channel.writeByte(collectionType);
        channel.writeInt(OMultiValue.getSize(result));
        for (Object o : OMultiValue.getMultiValueIterable(result)) {
          try {
            if (load && o instanceof ORecordId)
              o = ((ORecordId) o).getRecord();
            if (listener != null)
              listener.result(o);

            OBinaryProtocolHelper.writeIdentifiable(channel, (OIdentifiable) o, recordSerializer);
          } catch (Exception e) {
            OLogManager.instance().warn(this, "Cannot serialize record: " + o);
          }
        }
      }

    } else {
      // ANY OTHER (INCLUDING LITERALS)
      writeSimpleValue(channel, listener, result, protocolVersion, recordSerializer);
    }
  }

  private void writeSimpleValue(OChannelBinary channel, SimpleValueFetchPlanCommandListener listener, Object result,
      int protocolVersion, String recordSerializer) throws IOException {

    if (protocolVersion >= OChannelBinaryProtocol.PROTOCOL_VERSION_35) {
      channel.writeByte((byte) 'w');
      ODocument document = new ODocument();
      document.field("result", result);
      OBinaryProtocolHelper.writeIdentifiable(channel, document, recordSerializer);
      if (listener != null)
        listener.linkdedBySimpleValue(document);
    } else {
      channel.writeByte((byte) 'a');
      final StringBuilder value = new StringBuilder(64);
      if (listener != null) {
        ODocument document = new ODocument();
        document.field("result", result);
        listener.linkdedBySimpleValue(document);
      }
      ORecordSerializerStringAbstract.fieldTypeToString(value, OType.getTypeByClass(result.getClass()), result);
      channel.writeString(value.toString());
    }
  }

  @Override
  public void read(OChannelBinaryAsynchClient network, OStorageRemoteSession session) throws IOException {
    try {
      // Collection of prefetched temporary record (nested projection record), to refer for avoid garbage collection.
      List<ORecord> temporaryResults = new ArrayList<ORecord>();

      boolean addNextRecord = true;
      if (asynch) {
        byte status;

        // ASYNCH: READ ONE RECORD AT TIME
        while ((status = network.readByte()) > 0) {
          final ORecord record = (ORecord) OChannelBinaryProtocol.readIdentifiable(network);
          if (record == null)
            continue;

          switch (status) {
          case 1:
            // PUT AS PART OF THE RESULT SET. INVOKE THE LISTENER
            if (addNextRecord) {
              addNextRecord = listener.result(record);
              database.getLocalCache().updateRecord(record);
            }
            break;

          case 2:
            if (record.getIdentity().getClusterId() == -2)
              temporaryResults.add(record);
            // PUT IN THE CLIENT LOCAL CACHE
            database.getLocalCache().updateRecord(record);
          }
        }
      } else {
        result = readSynchResult(network, database, temporaryResults);
        if (live) {
          final ODocument doc = ((List<ODocument>) result).get(0);
          final Integer token = doc.field("token");
          final Boolean unsubscribe = doc.field("unsubscribe");
          if (token != null) {
            OStorageRemote storage = (OStorageRemote) database.getStorage();
            if (Boolean.TRUE.equals(unsubscribe)) {
              if (storage.asynchEventListener != null)
                storage.asynchEventListener.unregisterLiveListener(token);
            } else {
              final OLiveResultListener listener = (OLiveResultListener) this.listener;
              final ODatabaseDocument dbCopy = database.copy();
              ORemoteConnectionPool pool = storage.connectionManager.getPool(network.getServerURL());
              storage.asynchEventListener.registerLiveListener(pool, token, new OLiveResultListener() {

                @Override
                public void onUnsubscribe(int iLiveToken) {
                  listener.onUnsubscribe(iLiveToken);
                  dbCopy.close();
                }

                @Override
                public void onLiveResult(int iLiveToken, ORecordOperation iOp) throws OException {
                  dbCopy.activateOnCurrentThread();
                  listener.onLiveResult(iLiveToken, iOp);
                }

                @Override
                public void onError(int iLiveToken) {
                  listener.onError(iLiveToken);
                  dbCopy.close();
                }
              });
            }
          } else {
            throw new OStorageException("Cannot execute live query, returned null token");
          }
        }
      }
      if (!temporaryResults.isEmpty()) {
        if (result instanceof OBasicResultSet<?>) {
          ((OBasicResultSet<?>) result).setTemporaryRecordCache(temporaryResults);
        }
      }
    } finally {
      // TODO: this is here because we allow query in end listener.
      session.commandExecuting = false;
      if (listener != null && !live)
        listener.end();
    }
  }

  protected Object readSynchResult(final OChannelBinaryAsynchClient network, final ODatabaseDocument database,
      List<ORecord> temporaryResults) throws IOException {

    final Object result;

    final byte type = network.readByte();
    switch (type) {
    case 'n':
      result = null;
      break;

    case 'r':
      result = OChannelBinaryProtocol.readIdentifiable(network);
      if (result instanceof ORecord)
        database.getLocalCache().updateRecord((ORecord) result);
      break;

    case 'l':
    case 's':
      final int tot = network.readInt();
      final Collection<OIdentifiable> coll;

      coll = type == 's' ? new HashSet<OIdentifiable>(tot) : new OBasicResultSet<OIdentifiable>(tot);
      for (int i = 0; i < tot; ++i) {
        final OIdentifiable resultItem = OChannelBinaryProtocol.readIdentifiable(network);
        if (resultItem instanceof ORecord)
          database.getLocalCache().updateRecord((ORecord) resultItem);
        coll.add(resultItem);
      }

      result = coll;
      break;
    case 'i':
      coll = new OBasicResultSet<OIdentifiable>();
      byte status;
      while ((status = network.readByte()) > 0) {
        final OIdentifiable record = OChannelBinaryProtocol.readIdentifiable(network);
        if (record == null)
          continue;
        if (status == 1) {
          if (record instanceof ORecord)
            database.getLocalCache().updateRecord((ORecord) record);
          coll.add(record);
        }
      }
      result = coll;
      break;
    case 'w':
      final OIdentifiable record = OChannelBinaryProtocol.readIdentifiable(network);
      // ((ODocument) record).setLazyLoad(false);
      result = ((ODocument) record).field("result");
      break;

    default:
      OLogManager.instance().warn(this, "Received unexpected result from query: %d", type);
      result = null;
    }

    if (network.getSrvProtocolVersion() >= 17) {
      // LOAD THE FETCHED RECORDS IN CACHE
      byte status;
      while ((status = network.readByte()) > 0) {
        final ORecord record = (ORecord) OChannelBinaryProtocol.readIdentifiable(network);
        if (record != null && status == 2) {
          // PUT IN THE CLIENT LOCAL CACHE
          database.getLocalCache().updateRecord(record);
          if (record.getIdentity().getClusterId() == -2)
            temporaryResults.add(record);
        }
      }
    }

    return result;
  }

  public Object getResult() {
    return result;
  }
}