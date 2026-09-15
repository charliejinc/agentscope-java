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
package io.agentscope.harness.agent.workspace;

import static io.agentscope.harness.agent.workspace.WorkspaceConstants.AGENTS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.AGENTS_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.KNOWLEDGE_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.KNOWLEDGE_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.MEMORY_MD;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SESSIONS_DIR;
import static io.agentscope.harness.agent.workspace.WorkspaceConstants.SESSIONS_STORE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the local-disk branch of {@code WorkspaceManager#readWithOverride}: the namespaced copy
 * wins, and a shared file at the bare workspace root stays readable while a namespace is active.
 */
class WorkspaceManagerNamespaceFallbackTest {

    private static final String NS = "alice";
    private static final NamespaceFactory USER_NS = rc -> List.of(NS);
    private static final NamespaceFactory NO_NS = rc -> List.of();
    private static final RuntimeContext RC = RuntimeContext.builder().userId(NS).build();

    /**
     * {@code filesystem} is deliberately {@code null} so {@code readTextThroughFilesystem} returns
     * "" and every read lands on the local-disk fallback under test.
     */
    private static WorkspaceManager manager(Path workspace, NamespaceFactory namespaceFactory) {
        return new WorkspaceManager(workspace, null, null, namespaceFactory);
    }

    private static void write(Path workspace, String relativePath, String content)
            throws Exception {
        Path path = workspace.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String namespaced(String relativePath) {
        return NS + "/" + relativePath;
    }

    @Test
    void namespacedCopyWinsOverWorkspaceRoot(@TempDir Path workspace) throws Exception {
        write(workspace, AGENTS_MD, "SHARED");
        write(workspace, namespaced(AGENTS_MD), "PER_USER");

        try (WorkspaceManager manager = manager(workspace, USER_NS)) {
            assertEquals("PER_USER", manager.readAgentsMd(RC));
        }
    }

    @Test
    void missingNamespacedCopyFallsBackToSharedWorkspaceRoot(@TempDir Path workspace)
            throws Exception {
        write(workspace, AGENTS_MD, "SHARED");
        write(workspace, MEMORY_MD, "SHARED_MEMORY");

        try (WorkspaceManager manager = manager(workspace, USER_NS)) {
            assertEquals("SHARED", manager.readAgentsMd(RC));
            assertEquals("SHARED_MEMORY", manager.readMemoryMd(RC));
        }
    }

    @Test
    void nestedKnowledgePathFallsBackToSharedWorkspaceRoot(@TempDir Path workspace)
            throws Exception {
        write(workspace, KNOWLEDGE_DIR + "/" + KNOWLEDGE_MD, "SHARED_KNOWLEDGE");

        try (WorkspaceManager manager = manager(workspace, USER_NS)) {
            assertEquals("SHARED_KNOWLEDGE", manager.readKnowledgeMd(RC));
        }
    }

    @Test
    void returnsEmptyWhenNeitherNamespacedNorRootCopyExists(@TempDir Path workspace) {
        try (WorkspaceManager manager = manager(workspace, USER_NS)) {
            assertEquals("", manager.readAgentsMd(RC));
        }
    }

    /**
     * Pins a sharp edge: a namespaced file that exists but is empty does <em>not</em> shadow the
     * shared root copy, because the fallback keys off content emptiness rather than file presence.
     */
    @Test
    void emptyNamespacedCopyDoesNotShadowSharedRoot(@TempDir Path workspace) throws Exception {
        write(workspace, AGENTS_MD, "SHARED");
        write(workspace, namespaced(AGENTS_MD), "");

        try (WorkspaceManager manager = manager(workspace, USER_NS)) {
            assertEquals("SHARED", manager.readAgentsMd(RC));
        }
    }

    @Test
    void emptyNamespaceReadsWorkspaceRootDirectly(@TempDir Path workspace) throws Exception {
        write(workspace, AGENTS_MD, "SHARED");

        try (WorkspaceManager manager = manager(workspace, NO_NS)) {
            assertEquals("SHARED", manager.readAgentsMd(RC));
        }
    }

    /** With no namespace both resolved paths are identical, so the equal-path guard returns "". */
    @Test
    void emptyNamespaceMissingFileReturnsEmpty(@TempDir Path workspace) {
        try (WorkspaceManager manager = manager(workspace, NO_NS)) {
            assertEquals("", manager.readAgentsMd(RC));
        }
    }

    @Test
    void nullNamespaceFactoryReadsWorkspaceRoot(@TempDir Path workspace) throws Exception {
        write(workspace, AGENTS_MD, "SHARED");

        try (WorkspaceManager manager = manager(workspace, null)) {
            assertEquals("SHARED", manager.readAgentsMd(RC));
            assertEquals("", manager.readMemoryMd(RC));
        }
    }

    /** Uses the factory {@code HarnessAgent} actually installs for the default USER scope. */
    @Test
    void isolationScopeUserFactoryFallsBackToSharedRoot(@TempDir Path workspace) throws Exception {
        write(workspace, AGENTS_MD, "SHARED");
        write(workspace, namespaced(MEMORY_MD), "PER_USER_MEMORY");

        try (WorkspaceManager manager =
                manager(workspace, IsolationScope.USER.toNamespaceFactory())) {
            assertEquals("SHARED", manager.readAgentsMd(RC));
            assertEquals("PER_USER_MEMORY", manager.readMemoryMd(RC));
        }
    }

    /**
     * Documents a known exposure rather than a desired behaviour: the root fallback is shared by
     * per-user runtime data, so a stale file left at the bare root becomes readable — and
     * rewritable through a read-modify-write — under an active namespace.
     */
    @Test
    void staleRuntimeDataAtBareRootIsVisibleUnderNamespace(@TempDir Path workspace)
            throws Exception {
        String relativePath = AGENTS_DIR + "/assistant/" + SESSIONS_DIR + "/" + SESSIONS_STORE;
        write(workspace, relativePath, "{\"stale\":true}");

        try (WorkspaceManager manager = manager(workspace, USER_NS)) {
            assertEquals(
                    "{\"stale\":true}", manager.readManagedWorkspaceFileUtf8(RC, relativePath));
        }
    }
}
