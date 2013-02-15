/*******************************************************************************
 * Copyright 2011 Netflix
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.netflix.astyanax.recipes.uniqueness;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ConsistencyLevel;
import com.netflix.astyanax.recipes.locks.BusyLockException;
import com.netflix.astyanax.recipes.locks.ColumnPrefixDistributedRowLock;
import com.netflix.astyanax.recipes.locks.StaleLockException;
import com.netflix.astyanax.util.TimeUUIDUtils;

/**
 * Check uniqueness for multiple rows.  This test is done by
 * 1.  First writing a unique column to all rows, in a single batch.  Include a TTL for some failure conditions.
 * 2.  Reading back the unique columns from each row (must be done in a separate call)
 *      and making sure there is only one such column
 * 3.  Committing the columns without a TTL
 * 
 * 
 * @author elandau
 * 
 */
public class MultiRowUniquenessConstraint implements UniquenessConstraint {

    private final Keyspace keyspace;

    private final List<ColumnPrefixDistributedRowLock<String>> locks = Lists.newArrayList();
    private Integer ttl = null;
    private ConsistencyLevel consistencyLevel = ConsistencyLevel.CL_LOCAL_QUORUM;
    private String lockColumn;
    private String prefix = ColumnPrefixDistributedRowLock.DEFAULT_LOCK_PREFIX;

    public MultiRowUniquenessConstraint(Keyspace keyspace) {
        this.keyspace = keyspace;
        this.lockColumn = TimeUUIDUtils.getUniqueTimeUUIDinMicros().toString();
    }

    /**
     * TTL to use for the uniquness operation. This is the TTL for the columns
     * to expire in the event of a client crash before the uniqueness can be
     * committed
     * @param ttl
     */
    public MultiRowUniquenessConstraint withTtl(Integer ttl) {
        this.ttl = ttl;
        return this;
    }

    /**
     * Specify the prefix that uniquely distinguishes the lock columns from data
     * columns
     * @param prefix
     */
    public MultiRowUniquenessConstraint withColumnPrefix(String prefix) {
        this.prefix = prefix;
        return this;
    }

    /**
     * Override the autogenerated lock column.
     * @param column
     */
    public MultiRowUniquenessConstraint withLockId(String column) {
        this.lockColumn = column;
        return this;
    }

    /**
     * Consistency level used
     * @param consistencyLevel
     */
    public MultiRowUniquenessConstraint withConsistencyLevel(ConsistencyLevel consistencyLevel) {
        this.consistencyLevel = consistencyLevel;
        return this;
    }

    /**
     * Add a row to the set of rows being tested for uniqueness
     * 
     * @param columnFamily
     * @param rowKey
     */
    public MultiRowUniquenessConstraint withRow(ColumnFamily<String, String> columnFamily, String rowKey) {
        locks.add(new ColumnPrefixDistributedRowLock<String>(keyspace, columnFamily, rowKey));
        return this;
    }

    /**
     * @return Return the lock column written to ALL rows
     */
    public String getLockColumn() {
        return this.lockColumn;
    }

    @Override
    public void acquire() throws NotUniqueException, Exception {
        acquireAndApplyMutation(null);
    }
    
    @Override
    public void acquireAndApplyMutation(Function<MutationBatch, Boolean> callback) throws NotUniqueException, Exception {
        long now = TimeUnit.MICROSECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);

        // Insert lock check column for all rows in a single batch mutation
        try {
            MutationBatch m = keyspace.prepareMutationBatch().setConsistencyLevel(consistencyLevel);
            for (ColumnPrefixDistributedRowLock<String> lock : locks) {
                lock.withConsistencyLevel(consistencyLevel)
                    .withColumnPrefix(prefix)
                    .withLockId(lockColumn)
                    .fillLockMutation(m, now, ttl);
            }
            m.execute();

            // Check each lock in order
            for (ColumnPrefixDistributedRowLock<String> lock : locks) {
                lock.verifyLock(now);
            }

            // Commit the unique columns
            m = keyspace.prepareMutationBatch();
            for (ColumnPrefixDistributedRowLock<String> lock : locks) {
                lock.fillLockMutation(m, null, null);
            }
            
            if (callback != null)
                callback.apply(m);
            
            m.execute();
        }
        catch (BusyLockException e) {
            release();
            throw new NotUniqueException(e);
        }
        catch (StaleLockException e) {
            release();
            throw new NotUniqueException(e);
        }
        catch (Exception e) {
            release();
            throw e;
        }    }
    
    @Override
    @Deprecated
    public void acquireAndMutate(final MutationBatch mutation) throws NotUniqueException, Exception {
        acquireAndApplyMutation(new Function<MutationBatch, Boolean>() {
            @Override
            public Boolean apply(@Nullable MutationBatch input) {
                if (mutation != null)
                    input.mergeShallow(mutation);
                return true;
            }
        });
    }

    @Override
    public void release() throws Exception {
        MutationBatch m = keyspace.prepareMutationBatch();
        for (ColumnPrefixDistributedRowLock<String> lock : locks) {
            lock.fillReleaseMutation(m, false);
        }
        m.execute();
    }

}
