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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.AtomicSessionStorage;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxSessionStorageTest {

    private static final String WORKSPACE_ROOT = "/home/user/workspace";

    @Test
    void readReturnsNullWhenFileDoesNotExist() {
        RecordingSandbox sandbox = new RecordingSandbox();
        sandbox.enqueue("test -f", new ExecResult(1, "", "no such file", false));
        SandboxSessionStorage storage = new SandboxSessionStorage(sandbox, WORKSPACE_ROOT);

        assertNull(storage.read("agents/test/session.json"));
    }

    @Test
    void readParsesVersionAndPayload() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] onDisk = ByteBuffer.allocate(8 + payload.length).putLong(3).put(payload).array();
        String b64 = Base64.getEncoder().encodeToString(onDisk);

        RecordingSandbox sandbox = new RecordingSandbox();
        sandbox.enqueue("test -f", new ExecResult(0, b64, "", false));
        SandboxSessionStorage storage = new SandboxSessionStorage(sandbox, WORKSPACE_ROOT);

        AtomicSessionStorage.Value value = storage.read("agents/test/session.json");

        assertNotNull(value);
        assertEquals(3, value.version());
        assertArrayEquals(payload, value.bytes());
    }

    @Test
    void compareAndSetWritesWithCorrectVersion() {
        RecordingSandbox sandbox = new RecordingSandbox();
        sandbox.enqueue("test -f", new ExecResult(1, "", "", false));
        sandbox.enqueue("mkdir -p", new ExecResult(0, "", "", false));
        SandboxSessionStorage storage = new SandboxSessionStorage(sandbox, WORKSPACE_ROOT);

        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        assertTrue(storage.compareAndSet("agents/test/session.json", 0, data));

        String writeCmd = sandbox.commands.get(1);
        assertTrue(writeCmd.contains("base64 -d"));
        assertTrue(writeCmd.contains(".agentscope-runtime/agents/test/session.json"));
    }

    @Test
    void compareAndSetFailsOnVersionMismatch() {
        byte[] existing = ByteBuffer.allocate(8 + 2).putLong(5).put((byte) 1).put((byte) 2).array();
        String b64 = Base64.getEncoder().encodeToString(existing);

        RecordingSandbox sandbox = new RecordingSandbox();
        sandbox.enqueue("test -f", new ExecResult(0, b64, "", false));
        SandboxSessionStorage storage = new SandboxSessionStorage(sandbox, WORKSPACE_ROOT);

        assertFalse(storage.compareAndSet("agents/test/session.json", 3, new byte[] {9}));
        assertEquals(1, sandbox.commands.size());
    }

    @Test
    void listPathsReturnsRelativePaths() {
        String findOutput =
                WORKSPACE_ROOT
                        + "/.agentscope-runtime/agents/a/session.json\n"
                        + WORKSPACE_ROOT
                        + "/.agentscope-runtime/agents/b/session.json\n";
        RecordingSandbox sandbox = new RecordingSandbox();
        sandbox.enqueue("test -d", new ExecResult(0, findOutput, "", false));
        SandboxSessionStorage storage = new SandboxSessionStorage(sandbox, WORKSPACE_ROOT);

        List<String> paths = storage.listPaths("agents/");

        assertEquals(2, paths.size());
        assertEquals("agents/a/session.json", paths.get(0));
        assertEquals("agents/b/session.json", paths.get(1));
    }

    @Test
    void listPathsReturnsEmptyWhenDirectoryMissing() {
        RecordingSandbox sandbox = new RecordingSandbox();
        sandbox.enqueue("test -d", new ExecResult(1, "", "", false));
        SandboxSessionStorage storage = new SandboxSessionStorage(sandbox, WORKSPACE_ROOT);

        assertTrue(storage.listPaths("agents/").isEmpty());
    }

    @Test
    void roundTripCreateReadUpdate() {
        RecordingSandbox sandbox = new RecordingSandbox();
        SandboxSessionStorage storage = new SandboxSessionStorage(sandbox, WORKSPACE_ROOT);

        sandbox.enqueue("test -f", new ExecResult(1, "", "", false));
        sandbox.enqueue("mkdir -p", new ExecResult(0, "", "", false));
        byte[] v1 = "version-1".getBytes(StandardCharsets.UTF_8);
        assertTrue(storage.compareAndSet("agents/s1/session.json", 0, v1));

        String storagePath = WORKSPACE_ROOT + "/.agentscope-runtime/agents/s1/session.json";
        byte[] stored = sandbox.writtenFiles.get(storagePath);
        assertNotNull(stored);
        ByteBuffer buf = ByteBuffer.wrap(stored);
        assertEquals(1L, buf.getLong());
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        assertArrayEquals(v1, payload);

        String storedB64 = Base64.getEncoder().encodeToString(stored);
        sandbox.enqueue("test -f", new ExecResult(0, storedB64, "", false));
        AtomicSessionStorage.Value value = storage.read("agents/s1/session.json");
        assertEquals(1, value.version());
        assertArrayEquals(v1, value.bytes());

        sandbox.enqueue("test -f", new ExecResult(0, storedB64, "", false));
        sandbox.enqueue("mkdir -p", new ExecResult(0, "", "", false));
        byte[] v2 = "version-2".getBytes(StandardCharsets.UTF_8);
        assertTrue(storage.compareAndSet("agents/s1/session.json", 1, v2));

        byte[] updated = sandbox.writtenFiles.get(storagePath);
        ByteBuffer buf2 = ByteBuffer.wrap(updated);
        assertEquals(2L, buf2.getLong());
    }

    private static final class RecordingSandbox implements Sandbox {

        final Map<String, ExecResult> scriptedResults = new HashMap<>();
        final List<ExecResult> queue = new java.util.ArrayList<>();
        final List<String> commands = new java.util.ArrayList<>();
        final Map<String, byte[]> writtenFiles = new HashMap<>();
        private final SandboxState state = new SandboxState() {};

        RecordingSandbox() {
            WorkspaceSpec ws = new WorkspaceSpec();
            ws.setRoot(WORKSPACE_ROOT);
            state.setWorkspaceSpec(ws);
        }

        void enqueue(String commandPrefix, ExecResult result) {
            scriptedResults.put(commandPrefix, result);
            queue.add(result);
        }

        @Override
        public ExecResult exec(RuntimeContext rc, String command, Integer timeout) {
            commands.add(command);
            if (command.startsWith("mkdir -p") || command.contains("base64 -d >")) {
                String path = extractWritePath(command);
                if (path != null && command.contains("base64 -d")) {
                    String b64 = extractBase64Literal(command);
                    if (b64 != null) {
                        writtenFiles.put(path, Base64.getDecoder().decode(b64));
                    }
                }
                return queue.isEmpty() ? new ExecResult(0, "", "", false) : queue.remove(0);
            }
            for (Map.Entry<String, ExecResult> e : scriptedResults.entrySet()) {
                if (command.startsWith(e.getKey())) {
                    return e.getValue();
                }
            }
            if (!queue.isEmpty()) {
                return queue.remove(0);
            }
            return new ExecResult(0, "", "", false);
        }

        private String extractWritePath(String command) {
            int mvIdx = command.lastIndexOf("mv ");
            if (mvIdx >= 0) {
                String rest = command.substring(mvIdx + 3);
                String[] parts = rest.split("\\s+");
                if (parts.length >= 2) {
                    return unquote(parts[parts.length - 1]);
                }
            }
            return null;
        }

        private String extractBase64Literal(String command) {
            int printfIdx = command.indexOf("printf '%s' '");
            if (printfIdx < 0) return null;
            int start = printfIdx + "printf '%s' '".length();
            int end = command.indexOf("'", start);
            if (end < 0) return null;
            return command.substring(start, end);
        }

        private static String unquote(String s) {
            if (s.startsWith("'") && s.endsWith("'")) {
                return s.substring(1, s.length() - 1).replace("'\\''", "'");
            }
            return s;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return state;
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}

        @Override
        public void close() {}
    }
}
