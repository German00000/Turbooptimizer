package com.turbooptimizer.core.shizuku;

/**
 * Service executed by Shizuku in a separate process with the "shell" (ADB) identity.
 * It only runs commands accepted by CommandPolicy (validated again inside the service).
 */
interface IShellService {
    /** Reserved by Shizuku to destroy the service. */
    void destroy() = 16777114;

    /**
     * Runs a command as an argument list (no shell interpreter, no pipes).
     * Returns the result encoded with ShellResult.encode().
     */
    String exec(in String[] command, long timeoutMs) = 1;
}
