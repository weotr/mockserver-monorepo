package org.mockserver.state;

import java.util.List;

/**
 * Immutable snapshot of cluster membership and health for the
 * {@code GET /mockserver/cluster} operability endpoint.
 * <p>
 * For a single-node / in-memory deployment this is a degenerate snapshot:
 * {@code clustered=false}, exactly one {@code member} (this node), and
 * {@code coordinator} equal to this node's id. A clustered backend
 * (e.g. Infinispan with JGroups) reports the real fleet membership and the
 * elected coordinator.
 *
 * @param clustered   whether this node participates in a multi-node cluster
 * @param nodeId      this node's unique id
 * @param coordinator the id/address of the cluster coordinator, or this
 *                    node's id when not clustered
 * @param clusterName the configured cluster name, or {@code null}/empty when
 *                    not clustered
 * @param members     the cluster members; never empty (always contains at
 *                    least this node)
 */
public record ClusterInfo(
    boolean clustered,
    String nodeId,
    String coordinator,
    String clusterName,
    List<Member> members
) {

    /**
     * A single cluster member.
     *
     * @param id          the member's id/address as a string
     * @param coordinator whether this member is the cluster coordinator
     * @param local       whether this member is the node serving the request
     */
    public record Member(String id, boolean coordinator, boolean local) {
    }

    /**
     * Builds the degenerate single-node snapshot used by all non-clustered
     * backends: one local member that is also the coordinator.
     *
     * @param nodeId this node's id
     * @return a single-node, non-clustered {@link ClusterInfo}
     */
    public static ClusterInfo singleNode(String nodeId) {
        return new ClusterInfo(
            false,
            nodeId,
            nodeId,
            null,
            List.of(new Member(nodeId, true, true))
        );
    }
}
