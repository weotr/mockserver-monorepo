package org.mockserver.model;

import java.util.Objects;

/**
 * Declarative TCP-layer fault/chaos injection profile, applied at the raw byte
 * level <em>before</em> HTTP decoding. Each fault type mirrors one of
 * Toxiproxy's named toxics:
 * <ul>
 *   <li><b>latency</b> — delay all inbound data by {@code latencyMs} milliseconds</li>
 *   <li><b>down</b> — silently drop all inbound data (service appears down)</li>
 *   <li><b>bandwidth</b> — throttle inbound data to {@code bandwidthBytesPerSec}</li>
 *   <li><b>slow_close</b> — delay the TCP FIN by 2 seconds</li>
 *   <li><b>timeout</b> — never send FIN; connection hangs on close</li>
 *   <li><b>reset_peer</b> — send TCP RST and close immediately</li>
 *   <li><b>slicer</b> — fragment inbound data into chunks of {@code slicerChunkSize} bytes</li>
 *   <li><b>limit_data</b> — close the connection after {@code limitDataBytes} bytes received</li>
 * </ul>
 * <p>
 * Profiles are registered in the {@link org.mockserver.mock.action.http.TcpChaosRegistry}
 * and applied by the {@code TcpChaosHandler} Netty handler. When multiple faults are
 * configured on the same profile, they are evaluated in priority order: down, resetPeer,
 * limitData, slicer, bandwidth, latency.
 * <p>
 * Follows the model field/{@code withX}/getter convention so it round-trips through
 * Jackson without a bespoke (de)serializer.
 */
public class TcpChaosProfile extends ObjectWithJsonToString {

    private int hashCode;
    private Long latencyMs;
    private Boolean down;
    private Long bandwidthBytesPerSec;
    private Boolean slowClose;
    private Boolean timeout;
    private Boolean resetPeer;
    private Integer slicerChunkSize;
    private Long limitDataBytes;

    public static TcpChaosProfile tcpChaosProfile() {
        return new TcpChaosProfile();
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public TcpChaosProfile withLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
        this.hashCode = 0;
        return this;
    }

    public Boolean getDown() {
        return down;
    }

    public TcpChaosProfile withDown(Boolean down) {
        this.down = down;
        this.hashCode = 0;
        return this;
    }

    public Long getBandwidthBytesPerSec() {
        return bandwidthBytesPerSec;
    }

    public TcpChaosProfile withBandwidthBytesPerSec(Long bandwidthBytesPerSec) {
        if (bandwidthBytesPerSec != null && bandwidthBytesPerSec < 1) {
            throw new IllegalArgumentException("bandwidthBytesPerSec must be >= 1, got " + bandwidthBytesPerSec);
        }
        this.bandwidthBytesPerSec = bandwidthBytesPerSec;
        this.hashCode = 0;
        return this;
    }

    public Boolean getSlowClose() {
        return slowClose;
    }

    public TcpChaosProfile withSlowClose(Boolean slowClose) {
        this.slowClose = slowClose;
        this.hashCode = 0;
        return this;
    }

    public Boolean getTimeout() {
        return timeout;
    }

    public TcpChaosProfile withTimeout(Boolean timeout) {
        this.timeout = timeout;
        this.hashCode = 0;
        return this;
    }

    public Boolean getResetPeer() {
        return resetPeer;
    }

    public TcpChaosProfile withResetPeer(Boolean resetPeer) {
        this.resetPeer = resetPeer;
        this.hashCode = 0;
        return this;
    }

    public Integer getSlicerChunkSize() {
        return slicerChunkSize;
    }

    public TcpChaosProfile withSlicerChunkSize(Integer slicerChunkSize) {
        if (slicerChunkSize != null && slicerChunkSize < 1) {
            throw new IllegalArgumentException("slicerChunkSize must be >= 1, got " + slicerChunkSize);
        }
        this.slicerChunkSize = slicerChunkSize;
        this.hashCode = 0;
        return this;
    }

    public Long getLimitDataBytes() {
        return limitDataBytes;
    }

    public TcpChaosProfile withLimitDataBytes(Long limitDataBytes) {
        if (limitDataBytes != null && limitDataBytes < 1) {
            throw new IllegalArgumentException("limitDataBytes must be >= 1, got " + limitDataBytes);
        }
        this.limitDataBytes = limitDataBytes;
        this.hashCode = 0;
        return this;
    }

    /**
     * Returns {@code true} when at least one fault is configured (non-null and meaningful).
     */
    public boolean hasAnyFault() {
        return (latencyMs != null && latencyMs > 0)
            || Boolean.TRUE.equals(down)
            || (bandwidthBytesPerSec != null && bandwidthBytesPerSec > 0)
            || Boolean.TRUE.equals(slowClose)
            || Boolean.TRUE.equals(timeout)
            || Boolean.TRUE.equals(resetPeer)
            || (slicerChunkSize != null && slicerChunkSize > 0)
            || (limitDataBytes != null && limitDataBytes > 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        TcpChaosProfile that = (TcpChaosProfile) o;
        return Objects.equals(latencyMs, that.latencyMs)
            && Objects.equals(down, that.down)
            && Objects.equals(bandwidthBytesPerSec, that.bandwidthBytesPerSec)
            && Objects.equals(slowClose, that.slowClose)
            && Objects.equals(timeout, that.timeout)
            && Objects.equals(resetPeer, that.resetPeer)
            && Objects.equals(slicerChunkSize, that.slicerChunkSize)
            && Objects.equals(limitDataBytes, that.limitDataBytes);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(latencyMs, down, bandwidthBytesPerSec, slowClose, timeout, resetPeer, slicerChunkSize, limitDataBytes);
        }
        return hashCode;
    }
}
