package com.example.keepawake

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.Calendar

private const val TARGET = "com.android.systemui"
private const val DIR = "/data/user_de/0/com.android.systemui/files"

class MainHook : IXposedHookLoadPackage {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isLocked = false
    private var isScreenOn = true

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET) return

        val cl = lpparam.classLoader
        val clazz = XposedHelpers.findClassIfExists("com.android.systemui.keyguard.KeyguardViewMediator", cl) ?: return

        // 1. وقتی صفحه روشن می‌شود
        XposedHelpers.findAndHookMethod(clazz, "onStartedWakingUp", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                isScreenOn = true
                updateWakeLock(param.thisObject)
            }
        })

        // 2. وقتی صفحه خاموش می‌شود
        XposedHelpers.findAndHookMethod(clazz, "onStartedGoingToSleep", Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                isScreenOn = false
                updateWakeLock(param.thisObject)
            }
        })

        // 3. وقتی گوشی قفل می‌شود
        XposedHelpers.findAndHookMethod(clazz, "doKeyguardLocked", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                isLocked = true
                updateWakeLock(param.thisObject)
            }
        })

        // 4. وقتی گوشی آنلاک می‌شود
        XposedHelpers.findAndHookMethod(clazz, "doKeyguardUnlock", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                isLocked = false
                updateWakeLock(param.thisObject)
            }
        })
        
        // 5. چک کردن مداوم در صورت نیاز (بهبود پایداری در برخی رام‌ها)
        XposedHelpers.findAndHookMethod(clazz, "onScreenTurnedOn", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                isScreenOn = true
                updateWakeLock(param.thisObject)
            }
        })
    }

    private fun updateWakeLock(mediator: Any) {
        try {
            val ctx = XposedHelpers.getObjectField(mediator, "mContext") as? Context ?: return

            if (isLocked && isScreenOn && isScheduleActive()) {
                if (wakeLock == null) {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "KeepAwake:WakeLock")
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire()
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("KeepAwake Error: $t")
        }
    }

    private fun isScheduleActive(): Boolean {
        try {
            val fEnabled = File(DIR, "keep_awake_enabled")
            if (!fEnabled.exists() || fEnabled.readText().trim() != "1") return false

            val fStart = File(DIR, "keep_awake_start")
            val fEnd = File(DIR, "keep_awake_end")

            if (!fStart.exists() || !fEnd.exists()) return true

            val start = fStart.readText().trim()
            val end = fEnd.readText().trim()

            val cal = Calendar.getInstance()
            val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val startParts = start.split(":")
            val endParts = end.split(":")
            if (startParts.size != 2 || endParts.size != 2) return true

            val startMin = (startParts[0].toIntOrNull() ?: 0) * 60 + (startParts[1].toIntOrNull() ?: 0)
            val endMin = (endParts[0].toIntOrNull() ?: 0) * 60 + (endParts[1].toIntOrNull() ?: 0)

            // پشتیبانی از بازه‌هایی که از نیمه‌شب عبور می‌کنند
            return if (startMin <= endMin) {
                currentMin in startMin..endMin
            } else {
                currentMin >= startMin || currentMin <= endMin
            }
        } catch (_: Throwable) {
            return false
        }
    }
}
