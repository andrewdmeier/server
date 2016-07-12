
package com.torodb.mongodb.repl;

import com.eightkdata.mongowp.ErrorCode;
import com.eightkdata.mongowp.OpTime;
import com.eightkdata.mongowp.Status;
import com.eightkdata.mongowp.WriteConcern;
import com.eightkdata.mongowp.bson.BsonDocument;
import com.eightkdata.mongowp.bson.BsonObjectId;
import com.eightkdata.mongowp.bson.BsonValue;
import com.eightkdata.mongowp.bson.utils.DefaultBsonValues;
import com.eightkdata.mongowp.exceptions.MongoException;
import com.eightkdata.mongowp.exceptions.UnknownErrorException;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.DeleteCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.DeleteCommand.DeleteArgument;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.DeleteCommand.DeleteStatement;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.FindCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.FindCommand.FindArgument;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.FindCommand.FindResult;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.GetLastErrorCommand.WriteConcernEnforcementResult;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.InsertCommand;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.InsertCommand.InsertArgument;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.commands.general.InsertCommand.InsertResult;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.pojos.MemberState;
import com.eightkdata.mongowp.mongoserver.api.safe.library.v3m0.pojos.ReplicaSetConfig;
import com.eightkdata.mongowp.server.api.Request;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.AbstractIdleService;
import com.torodb.core.exceptions.user.UserException;
import com.torodb.core.transaction.RollbackException;
import com.torodb.mongodb.annotations.Locked;
import com.torodb.mongodb.annotations.MongoDBLayer;
import com.torodb.mongodb.core.MongodConnection;
import com.torodb.mongodb.core.MongodServer;
import com.torodb.mongodb.core.ReadOnlyMongodTransaction;
import com.torodb.mongodb.core.WriteMongodTransaction;
import com.torodb.mongodb.repl.exceptions.NoSyncSourceFoundException;
import com.torodb.mongodb.utils.DBCloner;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.eightkdata.mongowp.bson.utils.DefaultBsonValues.*;

/**
 *
 */
@ThreadSafe
@Singleton
public class ReplCoordinator extends AbstractIdleService implements ReplInterface {
    private static final Logger LOGGER = LogManager.getLogger(ReplCoordinator.class);
    private static final String CONSISTENT_DB = "torodb";
    private static final String CONSISTENT_COL = "repl.consistent";
    private final ReplCoordinatorOwnerCallback ownerCallback;
    private final ReadWriteLock lock;
    private final OplogReaderProvider oplogReaderProvider;
    private volatile MemberState memberState;
    private final Executor executor;
    private final OplogManager oplogManager;
    private final OplogOperationApplier oplogOpApplier;
    private final MongodServer server;
    private final SyncSourceProvider syncSourceProvider;
    private final DBCloner dbCloner;
    private final MongoClientProvider remoteClientProvider;
    private final BsonObjectId myRID;
    private final int myId;
    private final ObjectIdFactory objectIdFactory;

    private RecoveryService recoveryService;
    private SecondaryStateService secondaryService;
    private boolean consistent;

    @Inject
    public ReplCoordinator(
            ReplCoordinatorOwnerCallback ownerCallback,
            OplogReaderProvider orpProvider,
            OplogOperationApplier oplogOpApplier,
            MongodServer server,
            DBCloner dbCloner,
            MongoClientProvider remoteClientProvider,
            SyncSourceProvider syncSourceProvider,
            OplogManager oplogManager,
            @MongoDBLayer Executor replExecutor,
            ObjectIdFactory objectIdFactory) {
        this.ownerCallback = ownerCallback;
        this.executor = replExecutor;
        this.oplogReaderProvider = orpProvider;
        this.oplogManager = oplogManager;
        this.objectIdFactory = objectIdFactory;
        
        this.lock = new ReentrantReadWriteLock();

        recoveryService = null;
        secondaryService = null;
        memberState = null;

        this.myId = 123;
        this.myRID = objectIdFactory.consumeObjectId();

        this.dbCloner = dbCloner;
        this.remoteClientProvider = remoteClientProvider;
        this.oplogOpApplier = oplogOpApplier;
        this.server = server;
        this.syncSourceProvider = syncSourceProvider;
    }

    @Override
    protected Executor executor() {
        return executor;
    }

    @Override
    protected void startUp() throws Exception {
        LOGGER.info("Starting replication service");
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            //TODO: temporal implementation
            loadStoredConfig();
            oplogManager.startAsync();
            oplogManager.awaitRunning();
            loadConsistentState();

            if (iAmMaster()) {
                startPrimaryMode();
            }
            else {
                if (!isConsistent()) {
                    startRecoveryMode();
                }
                else {
                    startSecondaryMode();
                }
            }
        } finally {
            writeLock.unlock();
        }
        LOGGER.info("Replication service started");
    }

    @Override
    protected void shutDown() throws Exception {
        LOGGER.info("Shutting down replication service");
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            if (recoveryService != null) {
                stopRecoveryModeAsync();
            }
            if (secondaryService != null) {
                stopSecondaryModeAsync();
            }
            memberState = null;
        } finally {
            writeLock.unlock();
        }
        awaitRecoveryStopped();
        awaitSecondaryStopped();
        LOGGER.info("Replication service shutted down");
        ownerCallback.replCoordStopped();
    }

    @Override
    public void loadConfiguration(ReplicaSetConfig newConfig) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public MemberStateInterface freezeMemberState(boolean toChangeState) {
        Lock mutex;
        if (toChangeState) {
            mutex = lock.writeLock();
        }
        else {
            mutex = lock.readLock();
        }
        mutex.lock();

        switch (memberState) {
            case RS_PRIMARY: {
                return new MyPrimaryStateInterface(mutex, toChangeState);
            }
            case RS_SECONDARY: {
                return new MySecondaryStateInferface(mutex, toChangeState);
            }
            case RS_RECOVERING: {
                return new MyRecoveryStateInterface(mutex, toChangeState);
            }
            default: {
                throw new AssertionError("State " + memberState + " is not supported yet");
            }
        }
    }

    @Override
    public OplogManager getOplogManager() {
        return oplogManager;
    }

    @Override
    public long getSlaveDelaySecs() {
        return 0;
    }

    @Override
    public BsonObjectId getRID() {
        return myRID;
    }

    @Override
    public int getId() {
        return myId;
    }

    @Locked(exclusive = true)
    private void startRecoveryMode() {
        if (memberState != null && memberState.equals(MemberState.RS_RECOVERING)) {
            LOGGER.warn("Trying to start RECOVERY mode while we already are in that state");
            assert recoveryService != null;
            return ;
        }
        assert recoveryService == null;
        LOGGER.info("Starting RECOVERY mode");

        memberState = MemberState.RS_RECOVERING;
        recoveryService = new RecoveryService(
                new RecoveryServiceCallback(),
                oplogManager,
                syncSourceProvider,
                oplogReaderProvider,
                dbCloner,
                remoteClientProvider,
                server,
                oplogOpApplier,
                executor
        );
        recoveryService.startAsync();
    }

    @Locked(exclusive = true)
    private void stopRecoveryMode() {
        stopRecoveryModeAsync();
        awaitRecoveryStopped();
    }

    @Locked(exclusive = true)
    private void stopRecoveryModeAsync() {
        LOGGER.info("Stopping RECOVERY mode");
        recoveryService.stopAsync();
    }

    private void awaitRecoveryStopped() {
        if (recoveryService != null) {
            recoveryService.awaitTerminated();
            recoveryService = null;
        }
    }

    @Locked(exclusive = true)
    private void startSecondaryMode() {
        if (memberState != null && memberState.equals(MemberState.RS_SECONDARY)) {
            LOGGER.warn("Trying to start SECONDARY mode while we already are in that state");
            assert secondaryService != null;
            return ;
        }
        assert secondaryService == null;

        LOGGER.info("Starting SECONDARY mode");

        memberState = MemberState.RS_SECONDARY;
        secondaryService = new SecondaryStateService(
                new SecondaryServiceCallback(),
                oplogManager,
                oplogReaderProvider,
                oplogOpApplier,
                server,
                syncSourceProvider,
                executor
        );

        secondaryService.startAsync();
        try {
            secondaryService.awaitRunning();
        } catch (IllegalStateException ex) {
            LOGGER.error("Fatal error while starting secondary mode", ex);
            this.stopAsync();
        }
    }

    @Locked(exclusive = true)
    private void stopSecondaryMode() {
        Preconditions.checkState(memberState != null && memberState.equals(MemberState.RS_SECONDARY));

        assert secondaryService != null;

        stopSecondaryModeAsync();
        awaitSecondaryStopped();
    }

    @Locked(exclusive = true)
    private void stopSecondaryModeAsync() {
        LOGGER.info("Stopping SECONDARY mode");
        secondaryService.stopAsync();
    }

    private void awaitSecondaryStopped() {
        if (secondaryService != null) {
            secondaryService.awaitTerminated();
            secondaryService = null;
        }
    }

    @Locked(exclusive = true)
    private void startRollbackMode() {
        LOGGER.warn("Rollback request ignored. Starting recovery");
        startRecoveryMode();
    }

    @Locked(exclusive = true)
    private void startUnrecoverableMode() {
        LOGGER.info("Starting UNRECOVABLE mode");
        //TODO: Log somewhere
        memberState = null;
    }

    @Locked(exclusive = true)
    private void startPrimaryMode() {
        LOGGER.info("Starting PRIMARY mode");
        memberState = MemberState.RS_PRIMARY;
    }

    private boolean isConsistent() {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            return consistent;
        } finally {
            readLock.unlock();
        }
    }

    private Status<?> setConsistentState(boolean consistent) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            while (true) {
                try {
                    this.consistent = consistent;
                    Status<?> result = flushConsistentState();
                    LOGGER.info("Consistent state set to '" + consistent + "'");

                    return result;
                } catch (RollbackException ex) {
                    LOGGER.warn("Rollback while trying to set the consistent state", ex);
                }
            }
        }
        catch (Throwable ex) {
            LOGGER.error("It was impossible to store the consistent state", ex);
            return Status.from(ErrorCode.UNKNOWN_ERROR, "It was impossible to store the consistent state " + ex);
        } finally {
            writeLock.unlock();
        }
    }

    private Status<?> flushConsistentState() throws RollbackException {
        try (MongodConnection conn = server.openConnection();
                WriteMongodTransaction trans = conn.openWriteTransaction()) {
            Status<Long> deleteStatus = trans.execute(
                    new Request(CONSISTENT_DB, null, true, null),
                    DeleteCommand.INSTANCE,
                    new DeleteArgument(
                            CONSISTENT_COL,
                            Collections.singletonList(
                                    new DeleteStatement(
                                            DefaultBsonValues.EMPTY_DOC,
                                            true
                                    )
                            ),
                            true,
                            WriteConcern.fsync()
                    )
            );
            if (!deleteStatus.isOK()) {
                return deleteStatus;
            }

            Status<InsertResult> insertStatus = trans.execute(
                    new Request(CONSISTENT_DB, null, true, null),
                    InsertCommand.INSTANCE,
                    new InsertArgument(
                            CONSISTENT_COL,
                            Collections.singletonList(
                                    newDocument("consistent", newBoolean(consistent))
                            ),
                            WriteConcern.fsync(),
                            true,
                            null
                    )
            );
            if (!insertStatus.isOK()) {
                return insertStatus;
            }
            if (insertStatus.getResult().getN() != 1) {
                return Status.from(ErrorCode.UNKNOWN_ERROR, "An invalid number of documents has "
                        + "been inserted: " + insertStatus.getResult().getN());
            }
            trans.commit();
            return Status.ok();
        } catch (UserException ex) {
            return Status.from(ErrorCode.UNKNOWN_ERROR, "Unexpected user exception: " + ex);
        }
    }

    private void loadConsistentState() throws MongoException {
        try (MongodConnection conn = server.openConnection();
                ReadOnlyMongodTransaction trans = conn.openReadOnlyTransaction()) {
            Status<FindResult> findStatus = trans.execute(
                    new Request(CONSISTENT_DB, null, true, null),
                    FindCommand.INSTANCE,
                    new FindArgument.Builder()
                    .setCollection(CONSISTENT_COL)
                    .build()
            );
            if (!findStatus.isOK()) {
                throw new UnknownErrorException(findStatus.getErrorMsg());
            }
            boolean newConsistent;
            Iterator<BsonDocument> firstBatch = findStatus.getResult().getCursor().getFirstBatch();
            if (!firstBatch.hasNext()) {
                newConsistent = false;
            }
            else {
                BsonDocument doc = firstBatch.next();
                BsonValue consistentField = doc.get("consistent");
                newConsistent = consistentField != null && consistentField.isBoolean()
                        && consistentField.asBoolean().getValue();
            }
            consistent = newConsistent;
        }
    }

    private void loadStoredConfig() {
        LOGGER.warn("loadStoredConfig() is not implemented yet");
    }

    private boolean iAmMaster() {
        try {
            syncSourceProvider.calculateSyncSource(null);
        } catch (NoSyncSourceFoundException ex) {
            return true;
        }
        return false;
    }

    public static interface ReplCoordinatorOwnerCallback {
        public void replCoordStopped();
    }

    @NotThreadSafe
    private abstract class MyMemberStateInteface implements MemberStateInterface {
        private final Lock lock;
        private boolean closed;
        private final boolean canChangeState;

        public MyMemberStateInteface(Lock lock, boolean canChangeState) {
            this.lock = lock;
            this.closed = false;
            this.canChangeState = true;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }

        @Override
        public boolean canChangeMemberState() {
            return canChangeState;
        }
    }

    @NotThreadSafe
    private class MyPrimaryStateInterface extends MyMemberStateInteface implements PrimaryStateInterface {

        public MyPrimaryStateInterface(Lock lock, boolean canChangeState) {
            super(lock, canChangeState);
        }

        @Override
        public boolean canNodeAcceptWrites(String database) {
            return true;
        }

        @Override
        public void stepDown(boolean force, long waitTime, long stepDownTime) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public BsonObjectId getOurElectionId() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public MemberState getMemberState() {
            return MemberState.RS_PRIMARY;
        }

        @Override
        public WriteConcernEnforcementResult awaitReplication(OpTime ts, WriteConcern wc) {
            //TODO: trivial implementation
            return new WriteConcernEnforcementResult(
                    wc,
                    null,
                    0,
                    null,
                    false,
                    null,
                    ImmutableList.<HostAndPort>of()
            );
        }

        @Override
        public boolean canNodeAcceptReads(String database) {
            return true;
        }

    }

    private class MyRecoveryStateInterface extends MyMemberStateInteface implements RecoveryStateInterface {

        public MyRecoveryStateInterface(Lock lock, boolean canChangeState) {
            super(lock, canChangeState);
        }

        @Override
        public MemberState getMemberState() {
            return MemberState.RS_RECOVERING;
        }

        @Override
        public boolean canNodeAcceptWrites(String database) {
//            TODO: Check if the implementation is correct
//            return database.startsWith("local.");
            return false;
        }

        @Override
        public boolean canNodeAcceptReads(String database) {
            return false;
        }

    }

    @ThreadSafe
    private class MySecondaryStateInferface extends MyMemberStateInteface implements SecondaryStateInferface {
        public MySecondaryStateInferface(Lock lock, boolean canChangeState) {
            super(lock, canChangeState);
        }

        @Override
        public MemberState getMemberState() {
            return MemberState.RS_SECONDARY;
        }

        @Override
        public boolean canNodeAcceptWrites(String database) {
            return database.startsWith("local.");
        }

        @Override
        public boolean canNodeAcceptReads(String database) {
            return true;
        }

        @Override
        public void doPause() {
            assert secondaryService != null;
            secondaryService.doPause();
        }

        @Override
        public void doContinue() {
            assert secondaryService != null;
            secondaryService.doContinue();
        }

        @Override
        public boolean isPaused() {
            assert secondaryService != null;
            return secondaryService.isPaused();
        }
    }

    private class RecoveryServiceCallback implements RecoveryService.Callback {

        @Override
        public void recoveryFinished() {
            Lock writeLock = lock.writeLock();
            writeLock.lock();
            try {
                if (memberState != null && memberState.equals(MemberState.RS_RECOVERING)) {
                    stopRecoveryMode();
                    startSecondaryMode();
                }
                else {
                    LOGGER.info("Recovery finished, but before we can start "
                            + "secondary mode, the state changed to {}", memberState);
                }
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void recoveryFailed(Throwable ex) {
            LOGGER.error("Fatal error while starting recovery mode", ex);
            stopAsync();
        }

        @Override
        public void recoveryFailed() {
            LOGGER.error("Fatal error while starting recovery mode");
            stopAsync();
        }

        @Override
        public void setConsistentState(boolean consistent) {
            Status<?> status = ReplCoordinator.this.setConsistentState(consistent);
            if (!status.isOK()) {
                LOGGER.error("Fatal error: It was impossible to store the consistent state: {}", status);
                throw new AssertionError("Fatal error: It was impossible to store the consistent state: " + status);
            }
        }

        @Override
        public boolean canAcceptWrites(String database) {
            return true;
        }
    }

    private class SecondaryServiceCallback implements SecondaryStateService.Callback {

        @Override
        public void rollbackRequired() {
            Lock writeLock = lock.writeLock();
            writeLock.lock();
            try {
                if (MemberState.RS_SECONDARY.equals(memberState)) {
                    stopSecondaryMode();
                    startRollbackMode();
                }
                else {
                    LOGGER.info("Secondary request a rollback, but before we "
                            + "can start rollback mode, the state changed to {}",
                            memberState);
                }
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void impossibleToRecoverFromError(Status<?> status) {
            Lock writeLock = lock.writeLock();
            writeLock.lock();
            try {
                if (MemberState.RS_SECONDARY.equals(memberState)) {
                    stopSecondaryMode();
                    startUnrecoverableMode();
                }
                else {
                    LOGGER.info("Secondary request a rollback, but before we "
                            + "can start rollback mode, the state changed to {}",
                            memberState);
                }
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void impossibleToRecoverFromError(Throwable t) {
            impossibleToRecoverFromError(Status.from(ErrorCode.UNKNOWN_ERROR));
        }

    }
}
