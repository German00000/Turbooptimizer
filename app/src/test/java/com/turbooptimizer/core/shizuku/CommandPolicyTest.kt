package com.turbooptimizer.core.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPolicyTest {

    private fun allowed(vararg cmd: String) = CommandPolicy.check(cmd.toList()) is CommandPolicy.Verdict.Allowed
    private fun denied(vararg cmd: String) = CommandPolicy.check(cmd.toList()) is CommandPolicy.Verdict.Denied

    @Test fun allowsWhitelistedCommands() {
        assertTrue(allowed("am", "force-stop", "com.facebook.katana"))
        assertTrue(allowed("am", "kill", "com.example.app"))
        assertTrue(allowed("am", "kill-all"))
        assertTrue(allowed("pm", "disable-user", "--user", "0", "com.example.app"))
        assertTrue(allowed("pm", "enable", "--user", "0", "com.example.app"))
        assertTrue(allowed("pm", "trim-caches", "999G"))
        assertTrue(allowed("cmd", "appops", "set", "com.example.app", "RUN_ANY_IN_BACKGROUND", "ignore"))
        assertTrue(allowed("cmd", "game", "mode", "performance", "com.example.game"))
        assertTrue(allowed("settings", "put", "global", "animator_duration_scale", "0.5"))
        assertTrue(allowed("dumpsys", "meminfo", "-c"))
        assertTrue(allowed("cat", "/proc/stat"))
        assertTrue(allowed("cat", "/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq"))
        assertTrue(allowed("service", "call", "SurfaceFlinger", "1013"))
        assertTrue(allowed("dumpsys", "SurfaceFlinger", "--list"))
        assertTrue(allowed("dumpsys", "SurfaceFlinger", "--latency", "SurfaceView[com.example.game/com.example.game.Main]#0"))
    }

    @Test fun blocksSurfaceFlingerInjection() {
        assertTrue(denied("dumpsys", "SurfaceFlinger", "--latency"))
        assertTrue(denied("dumpsys", "SurfaceFlinger", "--latency", "-x"))
        assertTrue(denied("dumpsys", "SurfaceFlinger", "--latency", "layer;reboot"))
        assertTrue(denied("dumpsys", "SurfaceFlinger", "--latency", "a", "b"))
        assertTrue(denied("dumpsys", "SurfaceFlinger", "--set-debug"))
        assertTrue(denied("dumpsys", "SurfaceFlinger", "--list", "extra"))
    }

    @Test fun blocksCriticalPackages() {
        assertTrue(denied("am", "force-stop", "com.android.systemui"))
        assertTrue(denied("am", "force-stop", "android"))
        assertTrue(denied("am", "force-stop", "com.google.android.gms"))
        assertTrue(denied("am", "force-stop", "com.android.providers.media"))
        assertTrue(denied("pm", "disable-user", "--user", "0", "com.android.phone"))
        assertTrue(denied("cmd", "appops", "set", "com.android.systemui", "RUN_ANY_IN_BACKGROUND", "ignore"))
        assertTrue(denied("am", "force-stop", CommandPolicy.SHIZUKU_PACKAGE))
    }

    @Test fun blocksDangerousCommands() {
        assertTrue(denied("rm", "-rf", "/sdcard"))
        assertTrue(denied("sh", "-c", "reboot"))
        assertTrue(denied("pm", "uninstall", "com.example.app"))
        assertTrue(denied("pm", "clear", "com.example.app"))
        assertTrue(denied("settings", "put", "global", "adb_enabled", "0"))
        assertTrue(denied("settings", "put", "global", "animator_duration_scale", "10"))
        assertTrue(denied("cat", "/data/system/packages.xml"))
        assertTrue(denied("cat", "/proc/stat", "/etc/passwd"))
        assertTrue(denied("am", "force-stop", "com.example.app;reboot"))
        assertTrue(denied("am", "force-stop", "com.example.app", "extra"))
        assertTrue(denied("setprop", "debug.x", "1"))
        assertTrue(denied("dumpsys", "package"))
    }

    @Test fun shellResultRoundTrip() {
        val r = ShellResult(0, "salida\ncon líneas", "err")
        assertEquals(r, ShellResult.decode(ShellResult.encode(r)))
    }
}
