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
package io.agentscope.harness.agent.sandbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SandboxManagerReleasePolicyTest {

    private static final String AGENT_ID = "test-agent";

    private final SandboxClient<SandboxClientOptions> client = mock(SandboxClient.class);
    private final SessionSandboxStateStore stateStore = mock(SessionSandboxStateStore.class);
    private final Sandbox sandbox = mock(Sandbox.class);
    private final SandboxState state = mock(SandboxState.class);

    @Test
    void defaultPolicyStopsAndShutsDown() throws Exception {
        SandboxManager manager = new SandboxManager(client, stateStore, AGENT_ID);

        manager.release(acquireSelfManaged(manager));

        verify(sandbox).stop();
        verify(sandbox).shutdown();
    }

    @Test
    void snapshotOnlyPolicyKeepsSandboxAlive() throws Exception {
        SandboxManager manager =
                new SandboxManager(
                        client,
                        stateStore,
                        AGENT_ID,
                        SandboxExecutionGuard.noop(),
                        SandboxReleasePolicy.SNAPSHOT_ONLY);

        manager.release(acquireSelfManaged(manager));

        verify(sandbox).stop();
        verify(sandbox, never()).shutdown();
    }

    @Test
    void callerOwnedSandboxIsNeverStoppedRegardlessOfPolicy() throws Exception {
        SandboxManager manager =
                new SandboxManager(
                        client,
                        stateStore,
                        AGENT_ID,
                        SandboxExecutionGuard.noop(),
                        SandboxReleasePolicy.STOP_AND_SHUTDOWN);
        SandboxAcquireResult result =
                manager.acquire(SandboxContext.builder().externalSandbox(sandbox).build(), null);

        manager.release(result);

        verify(sandbox, never()).stop();
        verify(sandbox, never()).shutdown();
    }

    /** Priority 2 (caller-supplied state) still yields a self-managed result. */
    private SandboxAcquireResult acquireSelfManaged(SandboxManager manager) throws Exception {
        when(state.getSessionId()).thenReturn("s1");
        when(client.resume(state)).thenReturn(sandbox);
        return manager.acquire(SandboxContext.builder().externalSandboxState(state).build(), null);
    }
}
