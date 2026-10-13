/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.harness.agent.session;

import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sandbox-backed journal storage. Uses sandbox {@code exec} for all I/O and Java-side locks for
 * CAS atomicity. Session data lives in {@code .agentscope-runtime/} inside the sandbox workspace,
 * which is persisted across sandbox stop/start via the workspace snapshot mechanism.
 */
public final class SandboxSessionStorage implements AtomicSessionStorage {

    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final int TIMEOUT_SECONDS = 30;

    private final Sandbox sandbox;
    private final String root;

    public SandboxSessionStorage(Sandbox sandbox, String workspaceRoot) {
        this.sandbox = sandbox;
        this.root = workspaceRoot + "/.agentscope-runtime";
    }

    private String storagePath(String key) {
        return root + "/" + key;
    }

    @Override
    public Value read(String key) {
        String path = storagePath(key);
        ExecResult result;
        try {
            result =
                    sandbox.exec(
                            null,
                            "test -f " + shellQuote(path) + " && base64 " + shellQuote(path),
                            TIMEOUT_SECONDS);
        } catch (SandboxException.ExecException e) {
            return null;
        } catch (SandboxException.ExecTimeoutException e) {
            throw new SessionLogException("Journal read timed out: " + path, e);
        } catch (Exception e) {
            throw new SessionLogException("Journal read failed: " + path, e);
        }
        if (!result.ok() || result.stdout() == null || result.stdout().isBlank()) {
            return null;
        }
        byte[] decoded = Base64.getDecoder().decode(result.stdout().trim());
        if (decoded.length < 8) {
            throw new SessionLogException("Truncated journal object: " + path);
        }
        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        long version = buffer.getLong();
        if (version < 1) {
            throw new SessionLogException("Invalid journal version: " + path);
        }
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new Value(version, payload);
    }

    @Override
    public List<String> listPaths(String prefix) {
        String dir = storagePath(prefix);
        ExecResult result;
        try {
            result =
                    sandbox.exec(
                            null,
                            "test -d "
                                    + shellQuote(dir)
                                    + " && find "
                                    + shellQuote(dir)
                                    + " -type f | sort",
                            TIMEOUT_SECONDS);
        } catch (SandboxException.ExecException e) {
            return List.of();
        } catch (SandboxException.ExecTimeoutException e) {
            throw new SessionLogException("Journal discovery timed out: " + prefix, e);
        } catch (Exception e) {
            throw new SessionLogException("Journal discovery failed: " + prefix, e);
        }
        if (!result.ok() || result.stdout() == null || result.stdout().isBlank()) {
            return List.of();
        }
        String rootPrefix = root + "/";
        return result.stdout()
                .trim()
                .lines()
                .map(line -> line.replace('\\', '/'))
                .map(
                        line -> {
                            int idx = line.indexOf(rootPrefix);
                            return idx >= 0 ? line.substring(idx + rootPrefix.length()) : line;
                        })
                .filter(line -> !line.isEmpty())
                .toList();
    }

    @Override
    public boolean compareAndSet(String key, long expected, byte[] bytes) {
        String path = storagePath(key);
        String lockKey = root + "/" + key;
        ReentrantLock lock = LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Value current = read(key);
            if ((current == null ? 0 : current.version()) != expected) {
                return false;
            }
            byte[] encoded =
                    Base64.getEncoder()
                            .encode(
                                    ByteBuffer.allocate(8 + bytes.length)
                                            .putLong(expected + 1)
                                            .put(bytes)
                                            .array());
            String b64 = new String(encoded);
            String script =
                    "mkdir -p "
                            + shellQuote(parentPath(path))
                            + " && "
                            + "printf '%s' "
                            + shellQuote(b64)
                            + " | base64 -d > "
                            + shellQuote(path + ".tmp")
                            + " && "
                            + "mv "
                            + shellQuote(path + ".tmp")
                            + " "
                            + shellQuote(path);
            try {
                sandbox.exec(null, script, TIMEOUT_SECONDS);
            } catch (SandboxException.ExecException e) {
                throw new SessionLogException("Journal CAS failed: " + path, e);
            } catch (SandboxException.ExecTimeoutException e) {
                throw new SessionLogException("Journal CAS timed out: " + path, e);
            } catch (Exception e) {
                throw new SessionLogException("Journal CAS failed: " + path, e);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static String parentPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash > 0 ? path.substring(0, lastSlash) : ".";
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
