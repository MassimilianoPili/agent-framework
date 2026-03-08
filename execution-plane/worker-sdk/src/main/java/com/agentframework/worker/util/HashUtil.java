package com.agentframework.worker.util;

/**
 * @deprecated Use {@link com.agentframework.common.util.HashUtil} instead.
 *             This class delegates to the common module version.
 */
@Deprecated(since = "1.1.0", forRemoval = true)
public final class HashUtil {

    private HashUtil() {}

    /** @deprecated Use {@link com.agentframework.common.util.HashUtil#sha256(String)} */
    @Deprecated(since = "1.1.0", forRemoval = true)
    public static String sha256(String input) {
        return com.agentframework.common.util.HashUtil.sha256(input);
    }
}
