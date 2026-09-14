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
package io.agentscope.extensions.sandbox.e2b;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class E2bSandboxSnapshotCompressionTest {

    @Test
    void gzipRoundTripRestoresOriginalBytes() throws Exception {
        byte[] tar = repeatedTarPayload(64 * 1024);

        byte[] compressed = E2bSandbox.gzip(tar);

        assertTrue(
                compressed.length < tar.length,
                "expected compression to shrink the archive: "
                        + compressed.length
                        + " >= "
                        + tar.length);
        assertArrayEquals(tar, E2bSandbox.gunzipIfCompressed(compressed));
    }

    @Test
    void plainTarSnapshotPassesThroughUnchanged() throws Exception {
        byte[] legacy = "not-gzip-just-a-plain-tar".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(legacy, E2bSandbox.gunzipIfCompressed(legacy));
    }

    @Test
    void nativeSnapshotRefIsNotMistakenForCompressedTar() throws Exception {
        byte[] ref = E2bSnapshotRefs.encodeSnapshotId("snap-123");

        assertArrayEquals(ref, E2bSandbox.gunzipIfCompressed(ref));
        assertEquals("snap-123", E2bSnapshotRefs.decodeSnapshotIdIfPresent(ref));
    }

    private static byte[] repeatedTarPayload(int size) {
        byte[] unit =
                "./workspace/AGENTS.md 0000644 0000000 0000000 00000001432 14700000000 012345 0\0"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = unit[i % unit.length];
        }
        return out;
    }
}
