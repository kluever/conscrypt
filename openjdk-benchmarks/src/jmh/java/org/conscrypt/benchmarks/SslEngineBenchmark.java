/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.conscrypt.benchmarks;

import static java.lang.Math.max;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import org.conscrypt.OpenSSLProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Benchmark comparing performance of various engine implementations to conscrypt.
 */
@State(Scope.Benchmark)
public class SslEngineBenchmark {
    private static final byte[] CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".getBytes();
    private static final String PROTOCOL_TLS_V1_2 = "TLSv1.2";

    public enum SslProvider {
        JDK {
            private final SSLContext clientContext = initContext(newContext(), true);
            private final SSLContext serverContext = initContext(newContext(), false);

            @Override
            SSLEngine newClientEngine(String cipher) {
                return initEngine(clientContext.createSSLEngine(), cipher, true);
            }

            @Override
            SSLEngine newServerEngine(String cipher) {
                return initEngine(serverContext.createSSLEngine(), cipher, false);
            }

            private SSLContext newContext() {
                try {
                    return SSLContext.getInstance(PROTOCOL_TLS_V1_2);
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }
        },
        CONSCRYPT {
            private final SSLContext clientContext = initContext(newContext(), true);
            private final SSLContext serverContext = initContext(newContext(), false);

            @Override
            SSLEngine newClientEngine(String cipher) {
                return initEngine(clientContext.createSSLEngine(), cipher, true);
            }

            @Override
            SSLEngine newServerEngine(String cipher) {
                return initEngine(serverContext.createSSLEngine(), cipher, false);
            }

            private SSLContext newContext() {
                try {
                    return SSLContext.getInstance(PROTOCOL_TLS_V1_2, new OpenSSLProvider());
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            }
        },
        NETTY {
            private final SslContext clientContext = newClientContext();
            private final SslContext serverContext = newServerContext();

            @Override
            SSLEngine newClientEngine(String cipher) {
                return initEngine(
                        clientContext.newEngine(UnpooledByteBufAllocator.DEFAULT), cipher, true);
            }

            @Override
            SSLEngine newServerEngine(String cipher) {
                return initEngine(
                        serverContext.newEngine(UnpooledByteBufAllocator.DEFAULT), cipher, false);
            }

            private SslContext newClientContext() {
                try {
                    File clientCert = Util.loadCert("ca.pem");
                    return SslContextBuilder.forClient()
                            .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
                            .trustManager(clientCert)
                            .build();
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            }

            private SslContext newServerContext() {
                try {
                    File serverCert = Util.loadCert("server1.pem");
                    File serverKey = Util.loadCert("server1.key");
                    return SslContextBuilder.forServer(serverCert, serverKey)
                            .sslProvider(io.netty.handler.ssl.SslProvider.OPENSSL)
                            .build();
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        abstract SSLEngine newClientEngine(String cipher);
        abstract SSLEngine newServerEngine(String cipher);

        final SSLContext initContext(SSLContext context, boolean client) {
            if (client) {
                File cert = Util.loadCert("ca.pem");
                context = Util.initClientSslContext(context, cert);
            } else {
                File cert = Util.loadCert("server1.pem");
                File key = Util.loadCert("server1.key");
                context = Util.initServerContext(context, cert, key);
            }
            return context;
        }

        final SSLEngine initEngine(SSLEngine engine, String cipher, boolean client) {
            engine.setEnabledProtocols(new String[] {PROTOCOL_TLS_V1_2});
            engine.setEnabledCipherSuites(new String[] {cipher});
            engine.setUseClientMode(client);
            return engine;
        }
    }

    public enum BufferType {
        HEAP {
            @Override
            ByteBuffer newBuffer(int size) {
                return ByteBuffer.allocate(size);
            }
        },
        DIRECT {
            @Override
            ByteBuffer newBuffer(int size) {
                return ByteBuffer.allocateDirect(size);
            }
        };

        abstract ByteBuffer newBuffer(int size);
    }

    @Param public SslProvider sslProvider;

    @Param public BufferType bufferType;

    @Param({"64", "128", "512", "1024", "4096"}) public int messageSize;

    @Param({"TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"}) public String cipher;

    private SSLEngine clientEngine;
    private SSLEngine serverEngine;

    private ByteBuffer clientCleartextBuffer;
    private ByteBuffer encryptedBuffer;
    private ByteBuffer serverCleartextBuffer;

    @Setup
    public void setup() throws Exception {
        clientEngine = sslProvider.newClientEngine(cipher);
        serverEngine = sslProvider.newServerEngine(cipher);

        encryptedBuffer = bufferType.newBuffer(clientEngine.getSession().getPacketBufferSize());

        // Generate the message to be sent from the client.
        clientCleartextBuffer = bufferType.newBuffer(messageSize);
        serverCleartextBuffer = bufferType.newBuffer(
                max(messageSize, serverEngine.getSession().getApplicationBufferSize()));
        for (int i = 0; clientCleartextBuffer.hasRemaining(); i = (i + 1) % CHARS.length) {
            clientCleartextBuffer.put(CHARS[i]);
        }
        clientCleartextBuffer.flip();

        // Complete the initial TLS handshake.
        handshake();
    }

    /**
     * Simple benchmark that sends a single message from client to server.
     */
    @Benchmark
    public void sendMessage() throws SSLException {
        // Reset the buffers.
        clientCleartextBuffer.position(0);
        encryptedBuffer.clear();
        serverCleartextBuffer.clear();

        // Wrap the original message and create the encrypted data.
        SSLEngineResult wrapResult = clientEngine.wrap(clientCleartextBuffer, encryptedBuffer);
        if (wrapResult.getStatus() != SSLEngineResult.Status.OK) {
            throw new RuntimeException("Wrap returned unexpected result " + wrapResult);
        }

        // Unwrap the encrypted data and get back the original result.
        encryptedBuffer.flip();
        SSLEngineResult unwrapResult = serverEngine.unwrap(encryptedBuffer, serverCleartextBuffer);
        if (unwrapResult.getStatus() != SSLEngineResult.Status.OK) {
            throw new RuntimeException("Unwrap returned unexpected result " + wrapResult);
        }
        serverCleartextBuffer.flip();

        // Lightweight comparison - just make sure the unencrypted data length is correct.
        assertEquals(clientCleartextBuffer.limit(), serverCleartextBuffer.limit());
    }

    private void handshake() throws SSLException {
        ByteBuffer cTOs = allocateBuffer(clientEngine.getSession().getPacketBufferSize());
        ByteBuffer sTOc = allocateBuffer(serverEngine.getSession().getPacketBufferSize());

        ByteBuffer serverAppReadBuffer =
                allocateBuffer(serverEngine.getSession().getApplicationBufferSize());
        ByteBuffer clientAppReadBuffer =
                allocateBuffer(clientEngine.getSession().getApplicationBufferSize());

        clientEngine.beginHandshake();
        serverEngine.beginHandshake();

        ByteBuffer empty = allocateBuffer(0);

        SSLEngineResult clientResult;
        SSLEngineResult serverResult;

        boolean clientHandshakeFinished = false;
        boolean serverHandshakeFinished = false;

        do {
            int cTOsPos = cTOs.position();
            int sTOcPos = sTOc.position();

            clientResult = clientEngine.wrap(empty, cTOs);
            runDelegatedTasks(clientResult, clientEngine);
            serverResult = serverEngine.wrap(empty, sTOc);
            runDelegatedTasks(serverResult, serverEngine);

            // Verify that the consumed and produced number match what is in the buffers now.
            assertEquals(empty.remaining(), clientResult.bytesConsumed());
            assertEquals(empty.remaining(), serverResult.bytesConsumed());
            assertEquals(cTOs.position() - cTOsPos, clientResult.bytesProduced());
            assertEquals(sTOc.position() - sTOcPos, serverResult.bytesProduced());

            cTOs.flip();
            sTOc.flip();

            // Verify that we only had one SSLEngineResult.HandshakeStatus.FINISHED
            if (isHandshakeFinished(clientResult)) {
                assertFalse(clientHandshakeFinished);
                clientHandshakeFinished = true;
            }
            if (isHandshakeFinished(serverResult)) {
                assertFalse(serverHandshakeFinished);
                serverHandshakeFinished = true;
            }

            cTOsPos = cTOs.position();
            sTOcPos = sTOc.position();

            int clientAppReadBufferPos = clientAppReadBuffer.position();
            int serverAppReadBufferPos = serverAppReadBuffer.position();

            clientResult = clientEngine.unwrap(sTOc, clientAppReadBuffer);
            runDelegatedTasks(clientResult, clientEngine);
            serverResult = serverEngine.unwrap(cTOs, serverAppReadBuffer);
            runDelegatedTasks(serverResult, serverEngine);

            // Verify that the consumed and produced number match what is in the buffers now.
            assertEquals(sTOc.position() - sTOcPos, clientResult.bytesConsumed());
            assertEquals(cTOs.position() - cTOsPos, serverResult.bytesConsumed());
            assertEquals(clientAppReadBuffer.position() - clientAppReadBufferPos,
                    clientResult.bytesProduced());
            assertEquals(serverAppReadBuffer.position() - serverAppReadBufferPos,
                    serverResult.bytesProduced());

            cTOs.compact();
            sTOc.compact();

            // Verify that we only had one SSLEngineResult.HandshakeStatus.FINISHED
            if (isHandshakeFinished(clientResult)) {
                assertFalse(clientHandshakeFinished);
                clientHandshakeFinished = true;
            }
            if (isHandshakeFinished(serverResult)) {
                assertFalse(serverHandshakeFinished);
                serverHandshakeFinished = true;
            }
        } while (!clientHandshakeFinished || !serverHandshakeFinished);
    }

    private ByteBuffer allocateBuffer(int size) {
        return bufferType.newBuffer(size);
    }

    private static boolean isHandshakeFinished(SSLEngineResult result) {
        return result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED;
    }

    private static void runDelegatedTasks(SSLEngineResult result, SSLEngine engine) {
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            for (;;) {
                Runnable task = engine.getDelegatedTask();
                if (task == null) {
                    break;
                }
                task.run();
            }
        }
    }
}