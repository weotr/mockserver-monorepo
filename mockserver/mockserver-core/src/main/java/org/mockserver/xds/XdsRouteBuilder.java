package org.mockserver.xds;

import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import java.util.*;

/**
 * Builds a simplified xDS-compatible RouteConfiguration JSON from active MockServer
 * expectations. This is NOT a full xDS implementation -- it provides the route
 * configuration structure for tooling that reads xDS-format routes.
 */
public class XdsRouteBuilder {

    /**
     * Converts expectations to a simplified xDS RouteConfiguration structure.
     *
     * @param expectations the active expectations to convert
     * @return a map representing the xDS RouteConfiguration JSON
     */
    public Map<String, Object> buildRouteConfiguration(List<Expectation> expectations) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (Expectation expectation : expectations) {
            RequestDefinition requestDefinition = expectation.getHttpRequest();
            if (requestDefinition instanceof HttpRequest req) {
                Map<String, Object> route = new LinkedHashMap<>();
                Map<String, Object> match = new LinkedHashMap<>();

                if (req.getPath() != null && req.getPath().getValue() != null) {
                    match.put("path", req.getPath().getValue());
                }
                if (req.getMethod() != null && req.getMethod().getValue() != null
                    && !req.getMethod().getValue().isEmpty()) {
                    match.put("method", req.getMethod().getValue());
                }

                route.put("match", match);
                route.put("expectationId", expectation.getId());
                routes.add(route);
            }
        }

        Map<String, Object> virtualHost = new LinkedHashMap<>();
        virtualHost.put("name", "mockserver");
        virtualHost.put("domains", Collections.singletonList("*"));
        virtualHost.put("routes", routes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "mockserver_routes");
        result.put("virtual_hosts", Collections.singletonList(virtualHost));
        return result;
    }
}
