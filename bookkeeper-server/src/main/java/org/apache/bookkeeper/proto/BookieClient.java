package org.apache.bookkeeper.proto;

/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.ReadEntryCallback;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.WriteCallback;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.util.OrderedSafeExecutor;
import org.apache.bookkeeper.util.SafeRunnable;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implements the client-side part of the BookKeeper protocol.
 *
 */
public class BookieClient implements PerChannelBookieClientFactory {
    static final Logger LOG = LoggerFactory.getLogger(BookieClient.class);

    final OrderedSafeExecutor executor;
    final ClientSocketChannelFactory channelFactory;
    final ConcurrentHashMap<InetSocketAddress, PerChannelBookieClientPool> channels =
            new ConcurrentHashMap<InetSocketAddress, PerChannelBookieClientPool>();
    final HashedWheelTimer requestTimer;
    private final ClientConfiguration conf;
    private volatile boolean closed;
    private ReentrantReadWriteLock closeLock;
    private final StatsLogger statsLogger;
    private final int numConnectionsPerBookie;

    public BookieClient(ClientConfiguration conf, ClientSocketChannelFactory channelFactory, OrderedSafeExecutor executor) {
        this(conf, channelFactory, executor, NullStatsLogger.INSTANCE);
    }

    public BookieClient(ClientConfiguration conf, ClientSocketChannelFactory channelFactory, OrderedSafeExecutor executor,
                        StatsLogger statsLogger) {
        this.conf = conf;
        this.channelFactory = channelFactory;
        this.executor = executor;
        this.closed = false;
        this.closeLock = new ReentrantReadWriteLock();
        this.statsLogger = statsLogger;
        this.numConnectionsPerBookie = conf.getNumChannelsPerBookie();
        this.requestTimer = new HashedWheelTimer(
                new ThreadFactoryBuilder().setNameFormat("BookieClientTimer-%d").build(),
                conf.getTimeoutTimerTickDurationMs(), TimeUnit.MILLISECONDS,
                conf.getTimeoutTimerNumTicks());
    }

    @Override
    public PerChannelBookieClient create(InetSocketAddress address) {
        return new PerChannelBookieClient(conf, executor, channelFactory, address,
                                          requestTimer, statsLogger);
    }

    private PerChannelBookieClientPool lookupClient(InetSocketAddress addr, Object key) {
        PerChannelBookieClientPool clientPool = channels.get(addr);
        if (null == clientPool) {
            closeLock.readLock().lock();
            try {
                if (closed) {
                    return null;
                }
                PerChannelBookieClientPool newClientPool =
                        new DefaultPerChannelBookieClientPool(this, addr, numConnectionsPerBookie);
                PerChannelBookieClientPool oldClientPool = channels.putIfAbsent(addr, newClientPool);
                if (null == oldClientPool) {
                    clientPool = newClientPool;
                } else {
                    clientPool = oldClientPool;
                    newClientPool.close();
                }
            } finally {
                closeLock.readLock().unlock();
            }
        }
        return clientPool;
    }

    public void closeClients(Set<InetSocketAddress> addrs) {
        final HashSet<PerChannelBookieClientPool> clients =
                new HashSet<PerChannelBookieClientPool>();
        for (InetSocketAddress a : addrs) {
            PerChannelBookieClientPool c = channels.get(a);
            if (c != null) {
                clients.add(c);
            }
        }

        if (clients.size() == 0) {
            return;
        }
        executor.submit(new SafeRunnable() {
                @Override
                public void safeRun() {
                    for (PerChannelBookieClientPool c : clients) {
                        c.disconnect();
                    }
                }
            });
    }

    public void addEntry(final InetSocketAddress addr, final long ledgerId, final byte[] masterKey, final long entryId,
            final ChannelBuffer toSend, final WriteCallback cb, final Object ctx, final int options) {
        final PerChannelBookieClientPool client = lookupClient(addr, entryId);
        if (client == null) {
            cb.writeComplete(BKException.Code.BookieHandleNotAvailableException,
                             ledgerId, entryId, addr, ctx);
            return;
        }

        client.obtain(new GenericCallback<PerChannelBookieClient>() {
            @Override
            public void operationComplete(int rc, PerChannelBookieClient pcbc) {
                if (rc != BKException.Code.OK) {
                    cb.writeComplete(rc, ledgerId, entryId, addr, ctx);
                    return;
                }
                pcbc.addEntry(ledgerId, masterKey, entryId, toSend, cb, ctx, options);
            }
        });
    }

    public void readEntryAndFenceLedger(final InetSocketAddress addr,
                                        final long ledgerId,
                                        final byte[] masterKey,
                                        final long entryId,
                                        final ReadEntryCallback cb,
                                        final Object ctx) {
        final PerChannelBookieClientPool client = lookupClient(addr, entryId);
        if (client == null) {
            cb.readEntryComplete(BKException.Code.BookieHandleNotAvailableException,
                                 ledgerId, entryId, null, ctx);
            return;
        }

        client.obtain(new GenericCallback<PerChannelBookieClient>() {
            @Override
            public void operationComplete(int rc, PerChannelBookieClient pcbc) {
                if (rc != BKException.Code.OK) {
                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                    return;
                }
                pcbc.readEntryAndFenceLedger(ledgerId, masterKey, entryId, cb, ctx);
            }
        });
    }

    public void readEntry(final InetSocketAddress addr, final long ledgerId, final long entryId,
                          final ReadEntryCallback cb, final Object ctx) {
        final PerChannelBookieClientPool client = lookupClient(addr, entryId);
        if (client == null) {
            cb.readEntryComplete(BKException.Code.BookieHandleNotAvailableException,
                                 ledgerId, entryId, null, ctx);
            return;
        }

        client.obtain(new GenericCallback<PerChannelBookieClient>() {
            @Override
            public void operationComplete(int rc, PerChannelBookieClient pcbc) {

                if (rc != BKException.Code.OK) {
                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                    return;
                }
                pcbc.readEntry(ledgerId, entryId, cb, ctx);
            }
        });
    }

    public void readEntryWaitForLACUpdate(final InetSocketAddress addr, final long ledgerId, final long entryId, final long previousLAC,
                          final long timeOutInMillis, final boolean piggyBackEntry, final ReadEntryCallback cb, final Object ctx) {
        final PerChannelBookieClientPool client = lookupClient(addr, entryId);
        if (client == null) {
            cb.readEntryComplete(BKException.Code.BookieHandleNotAvailableException,
                ledgerId, entryId, null, ctx);
            return;
        }

        client.obtain(new GenericCallback<PerChannelBookieClient>() {
            @Override
            public void operationComplete(int rc, PerChannelBookieClient pcbc) {

                if (rc != BKException.Code.OK) {
                    cb.readEntryComplete(rc, ledgerId, entryId, null, ctx);
                    return;
                }
                pcbc.readEntryWaitForLACUpdate(ledgerId, entryId, previousLAC, timeOutInMillis, piggyBackEntry, cb, ctx);
            }
        });
    }

    public void close() {
        closeLock.writeLock().lock();
        try {
            closed = true;
            for (PerChannelBookieClientPool pool : channels.values()) {
                pool.close();
            }
        } finally {
            closeLock.writeLock().unlock();
        }
        // Shut down the timeout executor.
        this.requestTimer.stop();
    }

    private static class Counter {
        int i;
        int total;

        synchronized void inc() {
            i++;
            total++;
        }

        synchronized void dec() {
            i--;
            notifyAll();
        }

        synchronized void wait(int limit) throws InterruptedException {
            while (i > limit) {
                wait();
            }
        }

        synchronized int total() {
            return total;
        }
    }

    /**
     * @param args
     * @throws IOException
     * @throws NumberFormatException
     * @throws InterruptedException
     */
    public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
        if (args.length != 3) {
            System.err.println("USAGE: BookieClient bookieHost port ledger#");
            return;
        }
        WriteCallback cb = new WriteCallback() {

            public void writeComplete(int rc, long ledger, long entry, InetSocketAddress addr, Object ctx) {
                Counter counter = (Counter) ctx;
                counter.dec();
                if (rc != 0) {
                    System.out.println("rc = " + rc + " for " + entry + "@" + ledger);
                }
            }
        };
        Counter counter = new Counter();
        byte hello[] = "hello".getBytes();
        long ledger = Long.parseLong(args[2]);
        ClientSocketChannelFactory channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors
                .newCachedThreadPool());
        OrderedSafeExecutor executor = new OrderedSafeExecutor(1);
        BookieClient bc = new BookieClient(new ClientConfiguration(), channelFactory, executor);
        InetSocketAddress addr = new InetSocketAddress(args[0], Integer.parseInt(args[1]));

        for (int i = 0; i < 100000; i++) {
            counter.inc();
            bc.addEntry(addr, ledger, new byte[0], i, ChannelBuffers.wrappedBuffer(hello), cb, counter, 0);
        }
        counter.wait(0);
        System.out.println("Total = " + counter.total());
        channelFactory.releaseExternalResources();
        executor.shutdown();
    }
}
