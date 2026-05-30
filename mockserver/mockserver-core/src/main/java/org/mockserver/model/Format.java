package org.mockserver.model;

/**
 * @author jamesdbloom
 */
public enum Format {
    JAVA,
    JSON,
    LOG_ENTRIES,
    HAR,
    // Export formats — applicable to ACTIVE_EXPECTATIONS retrieval. Each
    // converts a list of expectations into a third-party tooling format that
    // can be imported elsewhere (specs, request collections).
    OPENAPI,
    POSTMAN,
    BRUNO,
    // cURL command(s) that reproduce recorded requests. Applicable to REQUESTS
    // and REQUEST_RESPONSES retrieval (not to expectation scopes).
    CURL
}
