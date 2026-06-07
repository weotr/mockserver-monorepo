package org.mockserver.netty.proxy;

import org.junit.Assume;
import org.junit.Test;

/**
 * Placeholder end-to-end integration test for eBPF-based original destination
 * resolution. This test is permanently skipped — the eBPF strategy was investigated
 * and deferred because it requires kernel-specific features (BTF, CAP_BPF, BPF
 * filesystem) and no mature lightweight Java BPF library exists.
 * <p>
 * <b>Design note:</b> see {@code docs/plans/g5-ebpf-original-dst.local.md} for the
 * full investigation: approach options (pinned BPF map, libbpf-java, pre-compiled
 * bytecode + JNA), kernel/capability requirements, and why the strategy was deferred.
 * <p>
 * <b>When to implement:</b>
 * <ul>
 *   <li>A lightweight, stable Java BPF library emerges (e.g., Project Panama FFI)</li>
 *   <li>The use case demands eBPF-specific features beyond original destination</li>
 *   <li>CI infrastructure supports BTF-enabled kernels reliably</li>
 * </ul>
 * <p>
 * <b>Approach when implemented:</b>
 * <ol>
 *   <li>BPF program (cgroup/sock_ops or sock_addr) stores original destination in a
 *       BPF map keyed by socket cookie</li>
 *   <li>Java resolver reads the map via pinned {@code /sys/fs/bpf/} or JNI
 *       {@code bpf(BPF_MAP_LOOKUP_ELEM)}</li>
 *   <li>Register in {@link CompositeOriginalDestinationResolver} chain</li>
 *   <li>Docker-gated e2e test with privileged container + BPF-capable kernel</li>
 * </ol>
 *
 * @see CompositeOriginalDestinationResolver
 */
public class EbpfOriginalDestinationEndToEndIT {

    @Test
    public void shouldResolveOriginalDestinationViaEbpf() {
        // eBPF original destination strategy: investigated and deferred.
        // See docs/plans/g5-ebpf-original-dst.local.md for the design note.
        Assume.assumeTrue(
            "eBPF original destination strategy is deferred — "
                + "requires kernel BTF, CAP_BPF, and a Java BPF library; "
                + "see docs/plans/g5-ebpf-original-dst.local.md",
            false
        );
    }
}
