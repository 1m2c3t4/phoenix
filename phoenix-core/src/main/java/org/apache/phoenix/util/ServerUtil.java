/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.util;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.concurrent.GuardedBy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.CoprocessorHConnection;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.client.RetriesExhaustedWithDetailsException;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.coprocessor.HashJoinCacheNotFoundException;
import org.apache.phoenix.exception.PhoenixIOException;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.hbase.index.table.CoprocessorHTableFactory;
import org.apache.phoenix.hbase.index.table.HTableFactory;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.hbase.index.util.VersionUtil;
import org.apache.phoenix.schema.StaleRegionBoundaryCacheException;


@SuppressWarnings("deprecation")
public class ServerUtil {
    private static final Log LOG = LogFactory.getLog(ServerUtil.class);
    private static final int COPROCESSOR_SCAN_WORKS = VersionUtil.encodeVersion("0.98.6");
    
    private static final String FORMAT = "ERROR %d (%s): %s";
    private static final Pattern PATTERN = Pattern.compile("ERROR (\\d+) \\((\\w+)\\): (.*)");
    private static final Pattern HASH_JOIN_EXCEPTION_PATTERN = Pattern.compile("joinId: (-?\\d+)");
    private static final Pattern PATTERN_FOR_TS = Pattern.compile(",serverTimestamp=(\\d+),");
    private static final String FORMAT_FOR_TIMESTAMP = ",serverTimestamp=%d,";
    private static final Map<Class<? extends Exception>, SQLExceptionCode> errorcodeMap
        = new HashMap<Class<? extends Exception>, SQLExceptionCode>();
    static {
        // Map a normal exception into a corresponding SQLException.
        errorcodeMap.put(ArithmeticException.class, SQLExceptionCode.SERVER_ARITHMETIC_ERROR);
    }

    public static void throwIOException(String msg, Throwable t) throws IOException {
        throw createIOException(msg, t);
    }

    public static IOException createIOException(String msg, Throwable t) {
        // First unwrap SQLExceptions if it's root cause is an IOException.
        if (t instanceof SQLException) {
            Throwable cause = t.getCause();
            if (cause instanceof IOException) {
                t = cause;
            }
        }
        // Throw immediately if DoNotRetryIOException
        if (t instanceof DoNotRetryIOException) {
            return (DoNotRetryIOException) t;
        } else if (t instanceof IOException) {
            // If the IOException does not wrap any exception, then bubble it up.
            Throwable cause = t.getCause();
            if (cause instanceof RetriesExhaustedWithDetailsException || cause instanceof DoNotRetryIOException)
            	return new DoNotRetryIOException(t.getMessage(), cause);
            else if (cause == null || cause instanceof IOException) {
                return (IOException) t;
            }
            // Else assume it's been wrapped, so throw as DoNotRetryIOException to prevent client hanging while retrying
            return new DoNotRetryIOException(t.getMessage(), cause);
        } else if (t instanceof SQLException) {
            // If it's already an SQLException, construct an error message so we can parse and reconstruct on the client side.
            return new DoNotRetryIOException(constructSQLErrorMessage((SQLException) t, msg), t);
        } else {
            // Not a DoNotRetryIOException, IOException or SQLException. Map the exception type to a general SQLException 
            // and construct the error message so it can be reconstruct on the client side.
            //
            // If no mapping exists, rethrow it as a generic exception.
            SQLExceptionCode code = errorcodeMap.get(t.getClass());
            if (code == null) {
                return new DoNotRetryIOException(msg + ": " + t.getMessage(), t);
            } else {
                return new DoNotRetryIOException(constructSQLErrorMessage(code, t, msg), t);
            }
        }
    }

    private static String constructSQLErrorMessage(SQLExceptionCode code, Throwable e, String message) {
        return constructSQLErrorMessage(code.getErrorCode(), code.getSQLState(), code.getMessage() + " " + e.getMessage() + " " + message);
    }

    private static String constructSQLErrorMessage(SQLException e, String message) {
        return constructSQLErrorMessage(e.getErrorCode(), e.getSQLState(), e.getMessage() + " " + message);
    }

    private static String constructSQLErrorMessage(int errorCode, String SQLState, String message) {
        return String.format(FORMAT, errorCode, SQLState, message);
    }

    public static SQLException parseServerException(Throwable t) {
        SQLException e = parseServerExceptionOrNull(t);
        if (e != null) {
            return e;
        }
        return new PhoenixIOException(t);
    }

    /**
     * Return the first SQLException in the exception chain, otherwise parse it.
     * When we're receiving an exception locally, there's no need to string parse,
     * as the SQLException will already be part of the chain.
     * @param t
     * @return the SQLException, or null if none found
     */
    public static SQLException parseLocalOrRemoteServerException(Throwable t) {
        while (t.getCause() != null) {
            if (t instanceof NotServingRegionException) {
                return parseRemoteException(new StaleRegionBoundaryCacheException());
            } else if (t instanceof SQLException) {
                return (SQLException) t;
            }
            t = t.getCause();
        }
        return parseRemoteException(t);
    }
    
    public static SQLException parseServerExceptionOrNull(Throwable t) {
        while (t.getCause() != null) {
            /*
             * Note that a NotServingRegionException could be thrown in multiple scenarios including splits, region
             * move, table disabled, etc. This is a hack and is meant to address the buggy behavior introduced in HBase
             * 0.98.21 and beyond. See HBASE-17122 for details.
             */
            if (t instanceof NotServingRegionException) {
                return parseRemoteException(new StaleRegionBoundaryCacheException());
            }
            t = t.getCause();
        }
        return parseRemoteException(t);
    }

    private static SQLException parseRemoteException(Throwable t) {
        
        String message = t.getLocalizedMessage();
        if (message != null) {
            // If the message matches the standard pattern, recover the SQLException and throw it.
            Matcher matcher = PATTERN.matcher(t.getLocalizedMessage());
            if (matcher.find()) {
                int statusCode = Integer.parseInt(matcher.group(1));
                SQLExceptionCode code = SQLExceptionCode.fromErrorCode(statusCode);
                if(code.equals(SQLExceptionCode.HASH_JOIN_CACHE_NOT_FOUND)){
                    Matcher m = HASH_JOIN_EXCEPTION_PATTERN.matcher(t.getLocalizedMessage());
                    if (m.find()) { return new HashJoinCacheNotFoundException(Long.parseLong(m.group(1))); }
                }
                return new SQLExceptionInfo.Builder(code).setMessage(matcher.group()).setRootCause(t).build().buildException();
            }
        }
        return null;
    }

    private static boolean coprocessorScanWorks(RegionCoprocessorEnvironment env) {
        return (VersionUtil.encodeVersion(env.getHBaseVersion()) >= COPROCESSOR_SCAN_WORKS);
    }
    
    /*
     * This code works around HBASE-11837 which causes HTableInterfaces retrieved from
     * RegionCoprocessorEnvironment to not read local data.
     */
    private static HTableInterface getTableFromSingletonPool(RegionCoprocessorEnvironment env, byte[] tableName) throws IOException {
        // It's ok to not ever do a pool.close() as we're storing a single
        // table only. The HTablePool holds no other resources that this table
        // which will be closed itself when it's no longer needed.
        @SuppressWarnings("resource")
        HTablePool pool = new HTablePool(env.getConfiguration(),1);
        try {
            return pool.getTable(tableName);
        } catch (RuntimeException t) {
            // handle cases that an IOE is wrapped inside a RuntimeException like HTableInterface#createHTableInterface
            if(t.getCause() instanceof IOException) {
                throw (IOException)t.getCause();
            } else {
                throw t;
            }
        }
    }
    
    public static HTableInterface getHTableForCoprocessorScan (RegionCoprocessorEnvironment env, HTableInterface writerTable) throws IOException {
        if (coprocessorScanWorks(env)) {
            return writerTable;
        }
        return getTableFromSingletonPool(env, writerTable.getTableName());
    }
    
    public static HTableInterface getHTableForCoprocessorScan (RegionCoprocessorEnvironment env, byte[] tableName) throws IOException {
        if (coprocessorScanWorks(env)) {
            return env.getTable(TableName.valueOf(tableName));
        }
        return getTableFromSingletonPool(env, tableName);
    }
    
    public static long parseServerTimestamp(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return parseTimestampFromRemoteException(t);
    }

    public static long parseTimestampFromRemoteException(Throwable t) {
        String message = t.getLocalizedMessage();
        if (message != null) {
            // If the message matches the standard pattern, recover the SQLException and throw it.
            Matcher matcher = PATTERN_FOR_TS.matcher(t.getLocalizedMessage());
            if (matcher.find()) {
                String tsString = matcher.group(1);
                if (tsString != null) {
                    return Long.parseLong(tsString);
                }
            }
        }
        return HConstants.LATEST_TIMESTAMP;
    }

    public static DoNotRetryIOException wrapInDoNotRetryIOException(String msg, Throwable t, long timestamp) {
        if (msg == null) {
            msg = "";
        }
        if (t instanceof SQLException) {
            msg = t.getMessage() + " " + msg;
        }
        msg += String.format(FORMAT_FOR_TIMESTAMP, timestamp);
        return new DoNotRetryIOException(msg, t);
    }
    
    public static boolean readyToCommit(int rowCount, long mutationSize, int maxBatchSize, long maxBatchSizeBytes) {
        return maxBatchSize > 0 && rowCount >= maxBatchSize
                || (maxBatchSizeBytes > 0 && mutationSize >= maxBatchSizeBytes);
    }
    
    public static boolean isKeyInRegion(byte[] key, HRegion region) {
        byte[] startKey = region.getRegionInfo().getStartKey();
        byte[] endKey = region.getRegionInfo().getEndKey();
        return (Bytes.compareTo(startKey, key) <= 0
                && (Bytes.compareTo(HConstants.LAST_ROW, endKey) == 0 || Bytes.compareTo(key,
                    endKey) < 0));
    }

    public static HTableFactory getDelegateHTableFactory(CoprocessorEnvironment env, Configuration conf) {
        if (env instanceof RegionCoprocessorEnvironment) {
            RegionCoprocessorEnvironment e = (RegionCoprocessorEnvironment) env;
            RegionServerServices services = e.getRegionServerServices();
            if (services instanceof HRegionServer) {
                return new CoprocessorHConnectionTableFactory(conf, (HRegionServer) services);
            }
        }
        return new CoprocessorHTableFactory(env);
    }

    /**
     * {@code HTableFactory} that creates HTables by using a {@link CoprocessorHConnection} This
     * factory was added as a workaround to the bug reported in
     * https://issues.apache.org/jira/browse/HBASE-18359
     */
    public static class CoprocessorHConnectionTableFactory implements HTableFactory {
        @GuardedBy("CoprocessorHConnectionTableFactory.this")
        private HConnection connection;
        private final Configuration conf;
        private final HRegionServer server;

        CoprocessorHConnectionTableFactory(Configuration conf, HRegionServer server) {
            this.conf = conf;
            this.server = server;
        }

        private synchronized HConnection getConnection(Configuration conf) throws IOException {
            if (connection == null || connection.isClosed()) {
                connection = new CoprocessorHConnection(conf, server);
            }
            return connection;
        }

        @Override
        public HTableInterface getTable(ImmutableBytesPtr tablename) throws IOException {
            return getConnection(conf).getTable(tablename.copyBytesIfNecessary());
        }

        @Override
        public synchronized void shutdown() {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (Throwable e) {
                LOG.warn("Error while trying to close the HConnection used by CoprocessorHConnectionTableFactory", e);
            }
        }

        @Override
        public HTableInterface getTable(ImmutableBytesPtr tablename, ExecutorService pool)
                throws IOException {
            return getConnection(conf).getTable(tablename.copyBytesIfNecessary(), pool);
        }
    }

}
