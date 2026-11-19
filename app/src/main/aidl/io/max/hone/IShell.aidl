package io.max.hone;

interface IShell {
    /** Shizuku calls this to stop the service. */
    void destroy() = 16777114;

    /** Runs [argv] as the shell user and returns its stdout. */
    String exec(in String[] argv) = 1;

    /** Clears the cache of each package; returns a JSON summary. */
    String clearCache(in String[] packages) = 2;
}
