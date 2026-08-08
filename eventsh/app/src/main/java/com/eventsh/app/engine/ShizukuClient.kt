package com.eventsh.app.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.widget.Toast
import rikka.shizuku.Shizuku

/**
 * Thin Shizuku integration. When the Shizuku service is running and the user
 * has granted permission, [execute] runs privileged shell commands inside the
 * Shizuku process (shell/root uid). This is the "no root" path for actions
 * that the standard API can no longer perform on Android 13+.
 *
 * The heavy lifting happens in [ShizukuCommandService], a Shizuku user service
 * bound here with [Shizuku.bindUserService].
 */
object ShizukuClient {
    private const val REQ_PERMISSION = 0x5A11

    @Volatile var ready = false
        private set
    @Volatile private var granted = false

    private var commandService: ICommandService? = null
    private var bound = false
    private var initialized = false
    private var appContext: Context? = null

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        appContext = ctx.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener { ready = false }
            Shizuku.addRequestPermissionResultListener { _, result ->
                granted = result == PackageManager.PERMISSION_GRANTED
                refresh()
            }
            refresh()
        } catch (e: Exception) {
            // Shizuku API not usable; every privileged call falls back to su
        }
    }

    private fun refresh() {
        val ctx = appContext ?: return
        try {
            val ping = Shizuku.pingBinder()
            granted = ping && (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            if (ping && granted) {
                bind(ctx)
            } else {
                ready = false
            }
        } catch (e: Exception) {
            ready = false
        }
    }

    private fun bind(ctx: Context) {
        if (bound) return
        bound = true
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                commandService = ICommandService.Stub.asInterface(binder)
                ready = commandService != null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                commandService = null
                ready = false
            }
        }
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(ctx, ShizukuCommandService::class.java))
                .processNameSuffix("shizuku_cmd")
                .version(1)
                .daemon(false)
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            bound = false
        }
    }

    /** True when Shizuku can currently execute privileged commands. */
    val available: Boolean get() = ready

    /** Executes a privileged shell command via Shizuku; null when unavailable/failed. */
    fun execute(cmd: String): String? {
        val svc = commandService ?: return null
        return try {
            svc.execute(cmd)
        } catch (e: Exception) {
            null
        }
    }

    /** Requests the Shizuku permission dialog (user must also allow in Shizuku). */
    fun requestPermission(ctx: Context) {
        try {
            if (Shizuku.pingBinder() && (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(ctx, "Shizuku permission already granted", Toast.LENGTH_SHORT).show()
                return
            }
            if (!Shizuku.pingBinder()) {
                Toast.makeText(ctx, "Shizuku is not running. Start the Shizuku app first.", Toast.LENGTH_SHORT).show()
                startShizukuApp(ctx)
                return
            }
            Shizuku.requestPermission(REQ_PERMISSION)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Shizuku unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startShizukuApp(ctx: Context) {
        try {
            ctx.startActivity(Intent("moe.shizuku.creator.intent.ACTION_SHIZUKU_INFO"))
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent("moe.shizuku.creator.SHIZUKU_INFO"))
            } catch (e2: Exception) {
                // no known activity - ignore
            }
        }
    }
}
