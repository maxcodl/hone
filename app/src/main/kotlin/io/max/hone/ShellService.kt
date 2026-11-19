package io.max.hone

import android.content.pm.IPackageDataObserver
import android.os.IBinder
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Runs inside Shizuku's process as the shell user. */
class ShellService : IShell.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(argv: Array<String>): String {
        val process = ProcessBuilder(*argv).redirectError(File("/dev/null")).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }

    /**
     * Clears each package's cache through the package manager, one at a time, waiting for each to finish.
     * Stops early when the package manager does not answer, which some builds do until a restart.
     */
    override fun clearCache(packages: Array<String>): String {
        val result = JSONObject().put("cleared", 0).put("failed", 0).put("stuck", false)
        try {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "package") as IBinder
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val delete = pm.javaClass.getMethod(
                "deleteApplicationCacheFilesAsUser", String::class.java, Int::class.javaPrimitiveType, IPackageDataObserver::class.java,
            )
            for (pkg in packages) {
                val done = CountDownLatch(1)
                var ok = false
                delete.invoke(pm, pkg, 0, object : IPackageDataObserver.Stub() {
                    override fun onRemoveCompleted(packageName: String?, succeeded: Boolean) {
                        ok = succeeded
                        done.countDown()
                    }
                })
                if (!done.await(15, TimeUnit.SECONDS)) return result.put("stuck", true).toString()
                val key = if (ok) "cleared" else "failed"
                result.put(key, result.getInt(key) + 1)
            }
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            result.put("error", "${cause.javaClass.simpleName}: ${cause.message}")
        }
        return result.toString()
    }
}
