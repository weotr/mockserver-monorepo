package org.mockserver.serialization.model;

import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;
import org.mockserver.model.TcpChaosProfile;

public class TcpChaosProfileDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<TcpChaosProfile> {

    private Long latencyMs;
    private Boolean down;
    private Long bandwidthBytesPerSec;
    private Boolean slowClose;
    private Boolean timeout;
    private Boolean resetPeer;
    private Integer slicerChunkSize;
    private Long limitDataBytes;
    private Boolean resetMidResponse;
    private Integer resetAfterResponseChunks;
    private DelayDTO slowCloseDelay;
    private Boolean http2GoAway;
    private Long http2GoAwayErrorCode;
    private Long http2GoAwayLastStreamId;

    public TcpChaosProfileDTO(TcpChaosProfile tcpChaosProfile) {
        if (tcpChaosProfile != null) {
            latencyMs = tcpChaosProfile.getLatencyMs();
            down = tcpChaosProfile.getDown();
            bandwidthBytesPerSec = tcpChaosProfile.getBandwidthBytesPerSec();
            slowClose = tcpChaosProfile.getSlowClose();
            timeout = tcpChaosProfile.getTimeout();
            resetPeer = tcpChaosProfile.getResetPeer();
            slicerChunkSize = tcpChaosProfile.getSlicerChunkSize();
            limitDataBytes = tcpChaosProfile.getLimitDataBytes();
            resetMidResponse = tcpChaosProfile.getResetMidResponse();
            resetAfterResponseChunks = tcpChaosProfile.getResetAfterResponseChunks();
            if (tcpChaosProfile.getSlowCloseDelay() != null) {
                slowCloseDelay = new DelayDTO(tcpChaosProfile.getSlowCloseDelay());
            }
            http2GoAway = tcpChaosProfile.getHttp2GoAway();
            http2GoAwayErrorCode = tcpChaosProfile.getHttp2GoAwayErrorCode();
            http2GoAwayLastStreamId = tcpChaosProfile.getHttp2GoAwayLastStreamId();
        }
    }

    public TcpChaosProfileDTO() {
    }

    @Override
    public TcpChaosProfile buildObject() {
        return TcpChaosProfile.tcpChaosProfile()
            .withLatencyMs(latencyMs)
            .withDown(down)
            .withBandwidthBytesPerSec(bandwidthBytesPerSec)
            .withSlowClose(slowClose)
            .withTimeout(timeout)
            .withResetPeer(resetPeer)
            .withSlicerChunkSize(slicerChunkSize)
            .withLimitDataBytes(limitDataBytes)
            .withResetMidResponse(resetMidResponse)
            .withResetAfterResponseChunks(resetAfterResponseChunks)
            .withSlowCloseDelay(slowCloseDelay != null ? slowCloseDelay.buildObject() : null)
            .withHttp2GoAway(http2GoAway)
            .withHttp2GoAwayErrorCode(http2GoAwayErrorCode)
            .withHttp2GoAwayLastStreamId(http2GoAwayLastStreamId);
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public TcpChaosProfileDTO setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
        return this;
    }

    public Boolean getDown() {
        return down;
    }

    public TcpChaosProfileDTO setDown(Boolean down) {
        this.down = down;
        return this;
    }

    public Long getBandwidthBytesPerSec() {
        return bandwidthBytesPerSec;
    }

    public TcpChaosProfileDTO setBandwidthBytesPerSec(Long bandwidthBytesPerSec) {
        this.bandwidthBytesPerSec = bandwidthBytesPerSec;
        return this;
    }

    public Boolean getSlowClose() {
        return slowClose;
    }

    public TcpChaosProfileDTO setSlowClose(Boolean slowClose) {
        this.slowClose = slowClose;
        return this;
    }

    public Boolean getTimeout() {
        return timeout;
    }

    public TcpChaosProfileDTO setTimeout(Boolean timeout) {
        this.timeout = timeout;
        return this;
    }

    public Boolean getResetPeer() {
        return resetPeer;
    }

    public TcpChaosProfileDTO setResetPeer(Boolean resetPeer) {
        this.resetPeer = resetPeer;
        return this;
    }

    public Integer getSlicerChunkSize() {
        return slicerChunkSize;
    }

    public TcpChaosProfileDTO setSlicerChunkSize(Integer slicerChunkSize) {
        this.slicerChunkSize = slicerChunkSize;
        return this;
    }

    public Long getLimitDataBytes() {
        return limitDataBytes;
    }

    public TcpChaosProfileDTO setLimitDataBytes(Long limitDataBytes) {
        this.limitDataBytes = limitDataBytes;
        return this;
    }

    public Boolean getResetMidResponse() {
        return resetMidResponse;
    }

    public TcpChaosProfileDTO setResetMidResponse(Boolean resetMidResponse) {
        this.resetMidResponse = resetMidResponse;
        return this;
    }

    public Integer getResetAfterResponseChunks() {
        return resetAfterResponseChunks;
    }

    public TcpChaosProfileDTO setResetAfterResponseChunks(Integer resetAfterResponseChunks) {
        this.resetAfterResponseChunks = resetAfterResponseChunks;
        return this;
    }

    public DelayDTO getSlowCloseDelay() {
        return slowCloseDelay;
    }

    public TcpChaosProfileDTO setSlowCloseDelay(DelayDTO slowCloseDelay) {
        this.slowCloseDelay = slowCloseDelay;
        return this;
    }

    public Boolean getHttp2GoAway() {
        return http2GoAway;
    }

    public TcpChaosProfileDTO setHttp2GoAway(Boolean http2GoAway) {
        this.http2GoAway = http2GoAway;
        return this;
    }

    public Long getHttp2GoAwayErrorCode() {
        return http2GoAwayErrorCode;
    }

    public TcpChaosProfileDTO setHttp2GoAwayErrorCode(Long http2GoAwayErrorCode) {
        this.http2GoAwayErrorCode = http2GoAwayErrorCode;
        return this;
    }

    public Long getHttp2GoAwayLastStreamId() {
        return http2GoAwayLastStreamId;
    }

    public TcpChaosProfileDTO setHttp2GoAwayLastStreamId(Long http2GoAwayLastStreamId) {
        this.http2GoAwayLastStreamId = http2GoAwayLastStreamId;
        return this;
    }
}
