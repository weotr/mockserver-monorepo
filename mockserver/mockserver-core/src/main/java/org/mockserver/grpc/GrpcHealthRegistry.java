package org.mockserver.grpc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GrpcHealthRegistry {

    private static final GrpcHealthRegistry INSTANCE = new GrpcHealthRegistry();

    private final ConcurrentHashMap<String, ServingStatus> statusByService = new ConcurrentHashMap<>();
    private volatile ServingStatus defaultStatus = ServingStatus.SERVING;

    GrpcHealthRegistry() {
    }

    public static GrpcHealthRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Set the status for a specific service name (empty string = default for all).
     */
    public void setStatus(String serviceName, ServingStatus status) {
        if (serviceName == null || status == null) {
            return;
        }
        if (serviceName.isEmpty()) {
            defaultStatus = status;
        } else {
            statusByService.put(serviceName, status);
        }
    }

    /**
     * Get the status for a service name; falls back to default.
     */
    public ServingStatus getStatus(String serviceName) {
        if (serviceName != null && !serviceName.isEmpty()) {
            ServingStatus s = statusByService.get(serviceName);
            if (s != null) {
                return s;
            }
        }
        return defaultStatus;
    }

    /**
     * Returns all non-default service to status entries plus the default status.
     */
    public Map<String, ServingStatus> entries() {
        Map<String, ServingStatus> result = new HashMap<>(statusByService);
        result.put("", defaultStatus);
        return result;
    }

    /**
     * Remove the override for a specific service so it reverts to the default. An empty service
     * name resets the default status itself back to SERVING.
     */
    public void removeStatus(String serviceName) {
        if (serviceName == null) {
            return;
        }
        if (serviceName.isEmpty()) {
            defaultStatus = ServingStatus.SERVING;
        } else {
            statusByService.remove(serviceName);
        }
    }

    public void reset() {
        statusByService.clear();
        defaultStatus = ServingStatus.SERVING;
    }
}
