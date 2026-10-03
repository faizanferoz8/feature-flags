package dev.flags.core;

import java.util.List;

/**
 * Every flag in one environment at one moment. SDKs hold one of these in memory and
 * evaluate against it locally.
 *
 * @param revision increases by one with every committed change to the environment, so a
 *     client that receives snapshots out of order can keep the newest
 */
public record Snapshot(String environment, long revision, List<FlagDefinition> flags) {

    public Snapshot {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}
