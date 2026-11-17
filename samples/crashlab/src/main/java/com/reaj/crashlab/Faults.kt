package com.reaj.crashlab

import android.system.Os
import android.system.OsConstants
import android.util.Log

/** Deliberate faults. Each one maps to an issue BugAssistant should report for com.reaj.crashlab. */
object Faults {
    private const val TAG = "CrashLab"
    private const val CHUNK_BYTES = 1 shl 20

    /** Strong references that are never cleared: this is the leak. */
    val leakedActivities = mutableListOf<LeakyActivity>()
    private val heapHog = mutableListOf<ByteArray>()

    /** App crash: uncaught exception on the main thread. */
    fun javaCrash(): Nothing = throw IllegalStateException("CrashLab: deliberate crash on the main thread")

    /** App crash from a worker thread. */
    fun backgroundCrash() {
        Thread({ throw IllegalArgumentException("CrashLab: deliberate crash on a worker thread") }, "crashlab-worker").start()
    }

    /** Native crash: a real SIGSEGV, so debuggerd writes a tombstone. */
    fun nativeCrash() {
        Os.kill(Os.getpid(), OsConstants.SIGSEGV)
    }

    /** ANR: blocks the main thread. Tap the screen during the block so the system notices. */
    fun freezeMainThread(seconds: Long) {
        Thread.sleep(seconds * 1000)
    }

    /** Fills the Java heap to [percent] of its limit and keeps it, for the "heap near its limit" check. */
    fun fillHeap(percent: Int): Long {
        val rt = Runtime.getRuntime()
        val target = rt.maxMemory() / 100 * percent
        try {
            while (rt.totalMemory() - rt.freeMemory() + CHUNK_BYTES < target) {
                heapHog += ByteArray(CHUNK_BYTES).also { it.fill(1) }
            }
        } catch (e: OutOfMemoryError) {
            heapHog.removeAt(heapHog.lastIndex)
            Log.w(TAG, "Stopped early: ${e.message}")
        }
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Allocates until the heap is exhausted. The OutOfMemoryError is not caught, so the app crashes. */
    fun outOfMemory(): Nothing {
        val hog = mutableListOf<ByteArray>()
        while (true) hog += ByteArray(CHUNK_BYTES).also { it.fill(1) }
    }

    fun release() {
        leakedActivities.clear()
        heapHog.clear()
        Runtime.getRuntime().gc()
    }

    fun heapText(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) shr 20
        return "Java heap: $used MB used of ${rt.maxMemory() shr 20} MB limit · Leaked Activities: ${leakedActivities.size}"
    }
}
