/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.server.lock.memory;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.netty.util.internal.ConcurrentSet;
import io.seata.common.exception.FrameworkException;
import io.seata.common.loader.LoadLevel;
import io.seata.common.util.CollectionUtils;
import io.seata.core.exception.TransactionException;
import io.seata.core.lock.AbstractLocker;
import io.seata.core.lock.RowLock;
import io.seata.server.session.BranchSession;

/**
 * The type Memory locker.
 *
 * @author zhangsen
 * @data 2019 -05-15
 */
@LoadLevel(name = "file")
public class MemoryLocker extends AbstractLocker {

    private static final int BUCKET_PER_TABLE = 128;

    private static final ConcurrentMap<String/* resourceId */,
        ConcurrentMap<String/* tableName */,
            ConcurrentMap<Integer/* bucketId */,
                ConcurrentMap<String/* pk */, Long/* transactionId */>>>>
        LOCK_MAP
        = new ConcurrentHashMap<>();

    /**
     * The Branch session.
     */
    protected BranchSession branchSession = null;

    /**
     * Instantiates a new Memory locker.
     *
     * @param branchSession the branch session
     */
    public MemoryLocker(BranchSession branchSession) {
        this.branchSession = branchSession;
    }

    @Override
    public boolean acquireLock(List<RowLock> rowLocks) {
        if (CollectionUtils.isEmpty(rowLocks)) {
            //no lock
            return true;
        }
        String resourceId = branchSession.getResourceId();
        long transactionId = branchSession.getTransactionId();

        ConcurrentMap<ConcurrentMap<String, Long>, Set<String>> bucketHolder = branchSession.getLockHolder();
        ConcurrentMap<String, ConcurrentMap<Integer, ConcurrentMap<String, Long>>> dbLockMap = LOCK_MAP.get(resourceId);
        if (dbLockMap == null) {
            LOCK_MAP.putIfAbsent(resourceId,
                new ConcurrentHashMap<>());
            dbLockMap = LOCK_MAP.get(resourceId);
        }

        for (RowLock lock : rowLocks) {
            String tableName = lock.getTableName();
            String pk = lock.getPk();
            ConcurrentMap<Integer, ConcurrentMap<String, Long>> tableLockMap = dbLockMap.get(tableName);
            if (tableLockMap == null) {
                dbLockMap.putIfAbsent(tableName, new ConcurrentHashMap<>());
                tableLockMap = dbLockMap.get(tableName);
            }
            int bucketId = pk.hashCode() % BUCKET_PER_TABLE;
            ConcurrentMap<String, Long> bucketLockMap = tableLockMap.get(bucketId);
            if (bucketLockMap == null) {
                tableLockMap.putIfAbsent(bucketId, new ConcurrentHashMap<>());
                bucketLockMap = tableLockMap.get(bucketId);
            }
            Long previousLockTransactionId = bucketLockMap.putIfAbsent(pk, transactionId);
            if (previousLockTransactionId == null) {
                //No existing lock, and now locked by myself
                Set<String> keysInHolder = bucketHolder.get(bucketLockMap);
                if (keysInHolder == null) {
                    bucketHolder.putIfAbsent(bucketLockMap, new ConcurrentSet<>());
                    keysInHolder = bucketHolder.get(bucketLockMap);
                }
                keysInHolder.add(pk);
            } else if (previousLockTransactionId == transactionId) {
                // Locked by me before
                continue;
            } else {
                LOGGER.info("Global lock on [" + tableName + ":" + pk + "] is holding by " + previousLockTransactionId);
                try {
                    // Release all acquired locks.
                    branchSession.unlock();
                } catch (TransactionException e) {
                    throw new FrameworkException(e);
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean releaseLock(List<RowLock> rowLock) {
        ConcurrentMap<ConcurrentMap<String, Long>, Set<String>> lockHolder = branchSession.getLockHolder();
        if (lockHolder == null || lockHolder.size() == 0) {
            return true;
        }
        for (Map.Entry<ConcurrentMap<String, Long>, Set<String>> entry : lockHolder.entrySet()) {
            ConcurrentMap<String, Long> bucket = entry.getKey();
            Set<String> keys = entry.getValue();
            for (String key : keys) {
                // remove lock only if it locked by myself
                bucket.remove(key, branchSession.getTransactionId());
            }
        }
        lockHolder.clear();
        return true;
    }

    @Override
    public boolean isLockable(List<RowLock> rowLocks) {
        if (CollectionUtils.isEmpty(rowLocks)) {
            //no lock
            return true;
        }
        Long transactionId = rowLocks.get(0).getTransactionId();
        String resourceId = rowLocks.get(0).getResourceId();
        ConcurrentMap<String, ConcurrentMap<Integer, ConcurrentMap<String, Long>>> dbLockMap = LOCK_MAP.get(resourceId);
        if (dbLockMap == null) {
            return true;
        }
        for (RowLock rowLock : rowLocks) {
            String xid = rowLock.getXid();
            String tableName = rowLock.getTableName();
            String pk = rowLock.getPk();

            ConcurrentMap<Integer, ConcurrentMap<String, Long>> tableLockMap = dbLockMap.get(tableName);
            if (tableLockMap == null) {
                continue;
            }
            int bucketId = pk.hashCode() % BUCKET_PER_TABLE;
            Map<String, Long> bucketLockMap = tableLockMap.get(bucketId);
            if (bucketLockMap == null) {
                continue;
            }
            Long lockingTransactionId = bucketLockMap.get(pk);
            if (lockingTransactionId == null || lockingTransactionId.longValue() == transactionId) {
                // Locked by me
                continue;
            } else {
                LOGGER.info("Global lock on [" + tableName + ":" + pk + "] is holding by " + lockingTransactionId);
                return false;
            }
        }
        return true;
    }

    @Override
    public void cleanAllLocks() {
        LOCK_MAP.clear();
    }
}
