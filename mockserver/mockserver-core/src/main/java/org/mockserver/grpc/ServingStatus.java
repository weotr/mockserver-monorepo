package org.mockserver.grpc;

public enum ServingStatus {
    UNKNOWN(0), SERVING(1), NOT_SERVING(2), SERVICE_UNKNOWN(3);

    private final int code;

    ServingStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static ServingStatus forCode(int code) {
        for (ServingStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return UNKNOWN;
    }
}
