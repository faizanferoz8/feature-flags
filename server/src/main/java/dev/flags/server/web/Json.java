package dev.flags.server.web;

import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper for JSON this application stores, as opposed to JSON it serves. Stored
 * documents outlive deployments, so their format is kept apart from whatever the web
 * layer's mapper is configured to do.
 */
public final class Json {

    public static final JsonMapper STORED = JsonMapper.builder().build();

    private Json() {}
}
