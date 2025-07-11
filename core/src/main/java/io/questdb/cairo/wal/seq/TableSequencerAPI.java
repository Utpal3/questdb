/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.wal.seq;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.ErrorTag;
import io.questdb.cairo.TableStructure;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.TableWriter;
import io.questdb.cairo.pool.ex.PoolClosedException;
import io.questdb.griffin.engine.ops.AlterOperation;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.ConcurrentHashMap;
import io.questdb.std.FilesFacade;
import io.questdb.std.ObjHashSet;
import io.questdb.std.QuietCloseable;
import io.questdb.std.str.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.util.Iterator;
import java.util.function.BiFunction;
import java.util.function.Function;

import static io.questdb.cairo.wal.ApplyWal2TableJob.WAL_2_TABLE_RESUME_REASON;
import static io.questdb.cairo.wal.WalUtils.SEQ_DIR;
import static io.questdb.cairo.wal.WalUtils.TXNLOG_FILE_NAME;

public class TableSequencerAPI implements QuietCloseable {
    private static final Log LOG = LogFactory.getLog(TableSequencerAPI.class);
    final CairoConfiguration configuration;
    final ConcurrentHashMap<TableSequencerImpl> seqRegistry = new ConcurrentHashMap<>(false);
    private final Function<CharSequence, SeqTxnTracker> createTxnTracker;
    private final CairoEngine engine;
    private final long inactiveTtlUs;
    private final int recreateDistressedSequencerAttempts;
    private final ConcurrentHashMap<SeqTxnTracker> seqTxnTrackers = new ConcurrentHashMap<>(false);
    private final BiFunction<CharSequence, Object, TableSequencerImpl> openSequencerInstanceLambda = this::openSequencerInstance;
    volatile boolean closed;

    public TableSequencerAPI(CairoEngine engine, CairoConfiguration configuration) {
        this.configuration = configuration;
        this.engine = engine;
        this.inactiveTtlUs = configuration.getInactiveWalWriterTTL() * 1000;
        this.recreateDistressedSequencerAttempts = configuration.getWalRecreateDistressedSequencerAttempts();
        this.createTxnTracker = dir -> new SeqTxnTracker(configuration);
    }

    public void applyRename(TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                sequencer.notifyRename(tableToken);
            } finally {
                sequencer.unlockWrite();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        releaseAll();
    }

    @TestOnly
    public void closeSequencer(TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                sequencer.close();
            } finally {
                sequencer.unlockWrite();
            }
        }
    }

    public void dropTable(TableToken tableToken, boolean failedCreate) {
        LOG.info().$("dropping wal table [table=").$(tableToken).I$();
        try (TableSequencerImpl seq = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                seq.dropTable();
            } finally {
                seq.unlockWrite();
            }
        } catch (CairoException e) {
            LOG.info().$("failed to drop wal table [table=").$(tableToken).I$();
            if (!failedCreate) {
                throw e;
            }
        }
    }

    public void forAllWalTables(ObjHashSet<TableToken> tableTokenBucket, boolean includeDropped, TableSequencerCallback callback) {
        final CharSequence root = configuration.getDbRoot();
        final FilesFacade ff = configuration.getFilesFacade();
        Path path = Path.PATH.get();

        engine.getTableTokens(tableTokenBucket, includeDropped);
        for (int i = 0, n = tableTokenBucket.size(); i < n; i++) {
            TableToken tableToken = tableTokenBucket.get(i);

            // Exclude locked entries.
            // Use includeDropped argument to decide whether to include dropped tables.
            String publicTableName = tableToken.getTableName();
            boolean isDropped = includeDropped && engine.isTableDropped(tableToken);
            if (engine.isWalTable(tableToken) && !isDropped) {
                long lastTxn;
                int tableId = tableToken.getTableId();

                try {
                    if (!seqRegistry.containsKey(tableToken.getDirName())) {
                        // Fast path.
                        // The following calls are racy, i.e. there might be a sequencer modifying both
                        // metadata and log concurrently as we read the values. It's ok since we iterate
                        // through the WAL tables periodically, so eventually we should see the updates.
                        path.of(root).concat(tableToken.getDirName()).concat(SEQ_DIR);
                        long fdTxn = TableUtils.openRO(ff, path, TXNLOG_FILE_NAME, LOG);
                        lastTxn = ff.readNonNegativeLong(fdTxn, TableTransactionLogFile.MAX_TXN_OFFSET_64); // does not throw
                        ff.close(fdTxn);
                    } else {
                        // Slow path.
                        try (TableSequencer tableSequencer = openSequencerLocked(tableToken, SequencerLockType.NONE)) {
                            lastTxn = tableSequencer.lastTxn();
                        }
                    }
                } catch (CairoException ex) {
                    if (ex.errnoFileCannotRead() || ex.isTableDropped()) {
                        // Table is partially dropped, but not fully.
                        lastTxn = -1;
                    } else {
                        LOG.critical().$("could not read WAL table transaction file [table=").$(tableToken)
                                .$(", errno=").$(ex.getErrno())
                                .$(", error=").$((Throwable) ex).I$();
                        continue;
                    }
                }

                try {
                    if (includeDropped || lastTxn > -1) {
                        callback.onTable(tableId, tableToken, lastTxn);
                    }
                } catch (CairoException ex) {
                    LOG.critical().$("could not process table sequencer [table=").$(tableToken)
                            .$(", errno=").$(ex.getErrno())
                            .$(", error=").$((Throwable) ex).I$();
                }
            } else if (isDropped) {
                try {
                    callback.onTable(tableToken.getTableId(), tableToken, -1);
                } catch (CairoException ex) {
                    LOG.critical().$("could not process table sequencer [table=").$(tableToken)
                            .$(", errno=").$(ex.getErrno())
                            .$(", error=").$((Throwable) ex).I$();
                }
            }
        }
    }

    public @NotNull TransactionLogCursor getCursor(final TableToken tableToken, long seqTxn) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            TransactionLogCursor cursor;
            try {
                cursor = tableSequencer.getTransactionLogCursor(seqTxn);
            } finally {
                tableSequencer.unlockRead();
            }
            return cursor;
        }
    }

    public @NotNull TableMetadataChangeLog getMetadataChangeLog(final TableToken tableToken, long structureVersionLo) {
        try (TableSequencerImpl tableSequencer = getOrOpenSequencer(tableToken, this.openSequencerInstanceLambda)) {
            if (tableSequencer.metadataMatches(structureVersionLo)) {
                return EmptyOperationCursor.INSTANCE;
            }
        }
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            try {
                return tableSequencer.getMetadataChangeLog(structureVersionLo);
            } finally {
                tableSequencer.unlockRead();
            }
        }
    }

    public TableMetadataChangeLog getMetadataChangeLogSlow(final TableToken tableToken, long structureVersionLo) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            TableMetadataChangeLog metadataChangeLog;
            try {
                metadataChangeLog = tableSequencer.getMetadataChangeLogSlow(structureVersionLo);
            } finally {
                tableSequencer.unlockRead();
            }
            return metadataChangeLog;
        }
    }

    public int getNextWalId(final TableToken tableToken) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            int walId;
            try {
                walId = tableSequencer.getNextWalId();
            } finally {
                tableSequencer.unlockRead();
            }
            return walId;
        }
    }

    public long getTableMetadata(final TableToken tableToken, final TableRecordMetadataSink sink) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            try {
                return tableSequencer.getTableMetadata(sink);
            } finally {
                tableSequencer.unlockRead();
            }
        }
    }

    public SeqTxnTracker getTxnTracker(TableToken tableToken) {
        return getSeqTxnTracker(tableToken);
    }

    public boolean initTxnTracker(TableToken tableToken, long writerTxn, long seqTxn) {
        SeqTxnTracker seqTxnTracker = getSeqTxnTracker(tableToken);
        final boolean isSuspended = isSuspended(tableToken);
        return seqTxnTracker.initTxns(writerTxn, seqTxn, isSuspended);
    }

    public boolean isSuspended(final TableToken tableToken) {
        return getSeqTxnTracker(tableToken).isSuspended();
    }

    public boolean isTxnTrackerInitialised(final TableToken tableToken) {
        return getSeqTxnTracker(tableToken).isInitialised();
    }

    public long lastTxn(final TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            long lastTxn;
            try {
                lastTxn = sequencer.lastTxn();
            } finally {
                sequencer.unlockRead();
            }
            return lastTxn;
        }
    }

    public long nextStructureTxn(final TableToken tableToken, long structureVersion, AlterOperation alterOp) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            long txn;
            try {
                txn = tableSequencer.nextStructureTxn(structureVersion, alterOp);
            } finally {
                tableSequencer.unlockWrite();
            }
            return txn;
        }
    }

    public long nextTxn(final TableToken tableToken, int walId, long expectedSchemaVersion, int segmentId, int segmentTxn, long txnMinTimestamp, long txnMaxTimestamp, long txnRowCount) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            long txn;
            try {
                txn = tableSequencer.nextTxn(expectedSchemaVersion, walId, segmentId, segmentTxn, txnMinTimestamp, txnMaxTimestamp, txnRowCount);
            } finally {
                tableSequencer.unlockWrite();
            }
            return txn;
        }
    }

    public boolean notifyOnCheck(TableToken tableToken, long seqTxn) {
        // Updates seqTxn and returns true if CheckWalTransactionsJob should post notification
        // to run ApplyWal2TableJob for the table
        return getSeqTxnTracker(tableToken).notifyOnCheck(seqTxn);
    }

    public void notifySegmentClosed(TableToken tableToken, long txn, int walId, int segmentId) {
        engine.getWalListener().segmentClosed(tableToken, txn, walId, segmentId);
    }

    @TestOnly
    public void openSequencer(TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            sequencer.unlockWrite();
        }
    }

    public boolean prepareToConvertToNonWal(final TableToken tableToken) {
        boolean isDropped;
        try (TableSequencerImpl seq = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            isDropped = seq.isDropped();
            seq.unlockWrite();
        } catch (CairoException e) {
            LOG.info().$("cannot open sequencer files, assumed table converted to non-wal [table=")
                    .$(tableToken).I$();
            return true;
        }

        final TableSequencerImpl tableSequencer = seqRegistry.get(tableToken.getDirName());
        if (tableSequencer != null && tableSequencer.checkClose()) {
            LOG.info().$("table is converted to non-WAL, closed table sequencer [table=").$(tableToken).I$();
            seqRegistry.remove(tableToken.getDirName(), tableSequencer);
        }
        return !isDropped;
    }

    public void purgeTxnTracker(String dirName) {
        seqTxnTrackers.remove(dirName);
    }

    public void registerTable(int tableId, final TableStructure tableDescriptor, final TableToken tableToken) {
        try (
                TableSequencerImpl tableSequencer = getTableSequencerEntry(
                        tableToken,
                        SequencerLockType.WRITE,
                        (key, tt) -> new TableSequencerImpl(
                                this,
                                engine,
                                tableToken,
                                getSeqTxnTracker((TableToken) tt),
                                tableId,
                                tableDescriptor
                        )
                )
        ) {
            SeqTxnTracker seqTxnTracker = getSeqTxnTracker(tableToken);
            seqTxnTracker.initTxns(0, 0, false);
            tableSequencer.unlockWrite();
        }
    }

    public boolean releaseAll() {
        seqTxnTrackers.clear();
        return releaseAll(Long.MAX_VALUE);
    }

    public boolean releaseInactive() {
        return releaseAll(configuration.getMicrosecondClock().getTicks() - inactiveTtlUs);
    }

    public TableToken reload(TableToken tableToken) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                return tableSequencer.reload();
            } finally {
                tableSequencer.unlockWrite();
            }
        }
    }

    public void reloadMetadataConditionally(
            final TableToken tableToken,
            long expectedStructureVersion,
            TableRecordMetadataSink sink
    ) {
        try (TableSequencerImpl tableSequencer = openSequencerLocked(tableToken, SequencerLockType.READ)) {
            try {
                if (tableSequencer.getStructureVersion() != expectedStructureVersion) {
                    tableSequencer.getTableMetadata(sink);
                }
            } finally {
                tableSequencer.unlockRead();
            }
        }
    }

    public void resumeTable(TableToken tableToken, long resumeFromTxn) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                if (!isSuspended(tableToken)) {
                    // Even if the table already unsuspended, send ApplyWal2TableJob notification anyway
                    // as a way to resume table which is not moving even if it's marked as not suspended.
                    sequencer.resumeTable();
                    return;
                }
                final long nextTxn = sequencer.lastTxn() + 1;
                if (resumeFromTxn > nextTxn) {
                    throw CairoException.nonCritical().put("resume txn is higher than next available transaction [resumeFromTxn=").put(resumeFromTxn).put(", nextTxn=").put(nextTxn).put(']');
                }
                // resume from the latest on negative value
                if (resumeFromTxn > 0) {
                    try (TableWriter tableWriter = engine.getWriter(tableToken, WAL_2_TABLE_RESUME_REASON)) {
                        long seqTxn = tableWriter.getAppliedSeqTxn();
                        if (resumeFromTxn - 1 > seqTxn) {
                            // including resumeFromTxn
                            tableWriter.commitSeqTxn(resumeFromTxn - 1);
                        }
                    }
                }
                sequencer.resumeTable();
            } finally {
                sequencer.unlockWrite();
            }
        }
    }

    @TestOnly
    public void setDistressed(TableToken tableToken) {
        try (TableSequencerImpl sequencer = openSequencerLocked(tableToken, SequencerLockType.WRITE)) {
            try {
                sequencer.setDistressed();
            } finally {
                sequencer.unlockWrite();
            }
        }
    }

    public void suspendTable(final TableToken tableToken, ErrorTag errorTag, String errorMessage) {
        getSeqTxnTracker(tableToken).setSuspended(errorTag, errorMessage);
    }

    /**
     * @see SeqTxnTracker#updateWriterTxns(long, long)
     */
    public boolean updateWriterTxns(final TableToken tableToken, long writerTxn, long dirtyWriterTxn) {
        return getSeqTxnTracker(tableToken).updateWriterTxns(writerTxn, dirtyWriterTxn);
    }

    private @NotNull TableSequencerImpl getOrOpenSequencer(
            TableToken tableToken,
            BiFunction<CharSequence, Object, TableSequencerImpl> lambda
    ) {
        int attempt = 0;
        while (attempt < recreateDistressedSequencerAttempts) {
            throwIfClosed();
            TableSequencerImpl entry = seqRegistry.computeIfAbsent(tableToken.getDirName(), tableToken, lambda);
            boolean isDistressed = entry.isDistressed();
            if (!isDistressed && !entry.isClosed()) {
                return entry;
            }

            if (isDistressed) {
                attempt++;
            }
        }

        throw CairoException.critical(0).put("sequencer is distressed [table=").put(tableToken.getDirName()).put(']');
    }

    private SeqTxnTracker getSeqTxnTracker(TableToken tt) {
        return seqTxnTrackers.computeIfAbsent(tt.getDirName(), createTxnTracker);
    }

    @NotNull
    private TableSequencerImpl getTableSequencerEntry(
            TableToken tableToken,
            SequencerLockType lock,
            BiFunction<CharSequence, Object, TableSequencerImpl> getSequencerLambda
    ) {
        TableSequencerImpl entry;
        int attempt = 0;
        while (attempt < recreateDistressedSequencerAttempts) {
            throwIfClosed();
            entry = seqRegistry.computeIfAbsent(tableToken.getDirName(), tableToken, getSequencerLambda);
            if (lock == SequencerLockType.READ) {
                entry.readLock();
            } else if (lock == SequencerLockType.WRITE) {
                entry.writeLock();
            }
            boolean isDistressed = entry.isDistressed();
            if (!isDistressed && !entry.isClosed()) {
                return entry;
            } else if (lock == SequencerLockType.READ) {
                entry.unlockRead();
            } else if (lock == SequencerLockType.WRITE) {
                entry.unlockWrite();
            }
            if (isDistressed) {
                attempt++;
            }
        }
        throw CairoException.critical(0).put("sequencer is distressed [table=").put(tableToken.getDirName()).put(']');
    }

    private TableSequencerImpl openSequencerInstance(CharSequence tableDir, Object tableToken) {
        return new TableSequencerImpl(
                this,
                this.engine,
                (TableToken) tableToken,
                getSeqTxnTracker((TableToken) tableToken),
                0,
                null
        );
    }

    @NotNull
    private TableSequencerImpl openSequencerLocked(TableToken tableToken, SequencerLockType lock) {
        return getTableSequencerEntry(tableToken, lock, this.openSequencerInstanceLambda);
    }

    private boolean releaseEntries(long deadline) {
        if (seqRegistry.isEmpty()) {
            // nothing to release
            return true;
        }
        boolean removed = false;
        final Iterator<CharSequence> iterator = seqRegistry.keySet().iterator();
        while (iterator.hasNext()) {
            final CharSequence tableDir = iterator.next();
            final TableSequencerImpl sequencer = seqRegistry.get(tableDir);
            if (sequencer != null && deadline >= sequencer.releaseTime && !sequencer.isClosed()) {
                // Remove from registry only if this thread closed the instance
                if (sequencer.checkClose()) {
                    LOG.info().$("releasing idle table sequencer [tableDir=").$safe(tableDir).I$();
                    iterator.remove();
                    removed = true;
                }
            }
        }
        return removed;
    }

    private void throwIfClosed() {
        if (closed) {
            LOG.info().$("is closed").$();
            throw PoolClosedException.INSTANCE;
        }
    }

    protected boolean releaseAll(long deadline) {
        return releaseEntries(deadline);
    }

    enum SequencerLockType {
        WRITE,
        READ,
        NONE
    }

    @FunctionalInterface
    public interface TableSequencerCallback {
        void onTable(int tableId, final TableToken tableName, long lastTxn);
    }
}
