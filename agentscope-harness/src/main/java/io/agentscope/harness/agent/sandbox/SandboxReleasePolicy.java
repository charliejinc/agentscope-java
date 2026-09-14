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

/**
 * What {@link SandboxManager#release} does with a harness-created sandbox once a call finishes.
 *
 * <p>Only applies to self-managed sandboxes; a caller-supplied sandbox ({@code
 * SandboxAcquireResult.isSelfManaged() == false}) is never stopped or destroyed regardless of this
 * policy.
 */
public enum SandboxReleasePolicy {

    /**
     * Persist the workspace snapshot, then destroy the sandbox. This is the default: no cloud
     * resource outlives the call, at the cost of recreating and rehydrating the workspace on the
     * next call for the same scope.
     */
    STOP_AND_SHUTDOWN,

    /**
     * Persist the workspace snapshot but leave the sandbox running, so the next call for the same
     * scope reconnects to it instead of paying creation plus workspace restore. The sandbox then
     * expires on its provider-side lifetime timeout.
     *
     * <p>Sandbox state is persisted either way, so a different process can resume the same live
     * sandbox; when the provider has already reclaimed it, resume falls back to create plus restore
     * exactly as under {@link #STOP_AND_SHUTDOWN}.
     */
    SNAPSHOT_ONLY
}
