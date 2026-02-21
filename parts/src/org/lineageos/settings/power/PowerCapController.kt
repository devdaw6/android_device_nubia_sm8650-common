/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.power

import android.util.Log
import org.lineageos.settings.utils.writeLine
import java.io.File

object PowerCapController {
    private const val TAG = "PowerCapController"
    private val warnedUnavailableNodes = mutableSetOf<String>()

    // Screen-on capped profile.
    private const val CAP_ON_LITTLE = 902_400      // ~0.90 GHz
    private const val CAP_ON_BIG = 1_401_600       // ~1.40 GHz
    private const val CAP_ON_PRIME = 1_593_600     // ~1.59 GHz

    // Extra-low screen-off profile.
    private const val CAP_OFF_SOFT_LITTLE = 672_000  // ~0.67 GHz
    private const val CAP_OFF_SOFT_BIG = 1_190_400   // ~1.19 GHz
    private const val CAP_OFF_SOFT_PRIME = 1_363_200 // ~1.36 GHz

    private const val CAP_OFF_LITTLE = 364_800     // ~0.36 GHz
    private const val CAP_OFF_BIG = 729_600        // ~0.73 GHz
    private const val CAP_OFF_PRIME = 787_200      // ~0.79 GHz

    // GPU (best-effort; skip if not writable)
    private const val CAP_ON_GPU = 244_224_000
    private const val CAP_OFF_GPU = 152_640_000
    private const val GPU_PATH = "/sys/class/kgsl/kgsl-3d0/devfreq"
    private const val GPU_MAX_PATH = "$GPU_PATH/max_freq"
    private const val GPU_MIN_PATH = "$GPU_PATH/min_freq"
    private const val GPU_AVAILABLE_PATH = "$GPU_PATH/available_frequencies"
    private const val GPU_MAX_FALLBACK_PATH = "/sys/class/kgsl/kgsl-3d0/max_gpuclk"

    private val littleCpus = intArrayOf(0, 1, 2, 3)
    private val bigCpus = intArrayOf(4, 5, 6)
    private val primeCpus = intArrayOf(7)

    private val stockLittleMax = readFreq("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq", 2_265_600)
    private val stockBigMax = readFreq("/sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq", 3_148_800)
    private val stockPrimeMax = readFreq("/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq", 3_398_400)

    private val stockLittleMin = readFreq("/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq", 364_800)
    private val stockBigMin = readFreq("/sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq", 499_200)
    private val stockPrimeMin = readFreq("/sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq", 480_000)

    private val stockGpuMax = readFreq(
        GPU_MAX_PATH,
        readFreq(GPU_MAX_FALLBACK_PATH, 915_000_000)
    )
    private val stockGpuMin = readFreq(GPU_MIN_PATH, 152_640_000)

    fun applyCapScreenOn() {
        setClusterCapped(littleCpus, CAP_ON_LITTLE)
        setClusterCapped(bigCpus, CAP_ON_BIG)
        setClusterCapped(primeCpus, CAP_ON_PRIME)
        setGpuCapped(CAP_ON_GPU)
        Log.i(TAG, "Applied screen-on capped clocks")
    }

    fun applyCapScreenOff() {
        setClusterCapped(littleCpus, CAP_OFF_LITTLE)
        setClusterCapped(bigCpus, CAP_OFF_BIG)
        setClusterCapped(primeCpus, CAP_OFF_PRIME)
        setGpuCapped(CAP_OFF_GPU)
        Log.i(TAG, "Applied screen-off minimum clocks")
    }

    fun applyCapScreenOffSoft() {
        setClusterCapped(littleCpus, CAP_OFF_SOFT_LITTLE)
        setClusterCapped(bigCpus, CAP_OFF_SOFT_BIG)
        setClusterCapped(primeCpus, CAP_OFF_SOFT_PRIME)
        setGpuCapped(CAP_ON_GPU)
        Log.i(TAG, "Applied screen-off soft clocks")
    }

    // Backward-compat helper.
    fun applyCap() {
        applyCapScreenOn()
    }

    fun applyStock() {
        setClusterStock(littleCpus, stockLittleMin, stockLittleMax)
        setClusterStock(bigCpus, stockBigMin, stockBigMax)
        setClusterStock(primeCpus, stockPrimeMin, stockPrimeMax)
        setGpuStock()
        Log.i(TAG, "Restored stock clocks")
    }

    private fun setClusterCapped(cpus: IntArray, requestedMax: Int) {
        val policyDirs = resolvePolicyDirs(cpus)
        val available = readClusterAvailableFreqs(policyDirs, cpus.first())
        val resolvedMax = resolveMax(available, requestedMax)
        val resolvedMin = available.firstOrNull() ?: resolvedMax
        policyDirs.forEach { dir ->
            writeNode("$dir/scaling_min_freq", resolvedMin)
            writeNode("$dir/scaling_max_freq", resolvedMax)
        }
    }

    private fun setClusterStock(cpus: IntArray, requestedMin: Int, requestedMax: Int) {
        val policyDirs = resolvePolicyDirs(cpus)
        val available = readClusterAvailableFreqs(policyDirs, cpus.first())
        val resolvedMax = resolveMax(available, requestedMax)
        val resolvedMin = resolveMin(available, requestedMin, resolvedMax)
        policyDirs.forEach { dir ->
            // Raise max first, then restore min (avoids min>max rejection when returning from cap mode).
            writeNode("$dir/scaling_max_freq", resolvedMax)
            writeNode("$dir/scaling_min_freq", resolvedMin)
        }
    }

    private fun setGpuCapped(requestedMax: Int) {
        val available = readAvailableFreqs(GPU_AVAILABLE_PATH)
        val resolvedMax = resolveMax(available, requestedMax)
        val resolvedMin = available.firstOrNull() ?: resolvedMax
        writeNode(GPU_MIN_PATH, resolvedMin)
        writeGpuMax(resolvedMax)
    }

    private fun setGpuStock() {
        val available = readAvailableFreqs(GPU_AVAILABLE_PATH)
        val resolvedMax = resolveMax(available, stockGpuMax)
        val resolvedMin = resolveMin(available, stockGpuMin, resolvedMax)
        writeGpuMax(resolvedMax)
        writeNode(GPU_MIN_PATH, resolvedMin)
    }

    private fun writeGpuMax(freq: Int) {
        if (!writeNode(GPU_MAX_PATH, freq)) {
            writeNode(GPU_MAX_FALLBACK_PATH, freq)
        }
    }

    private fun writeNode(path: String, value: Int): Boolean {
        val file = File(path)
        if (!file.exists() || !file.canWrite()) {
            if (warnedUnavailableNodes.add(path)) {
                Log.w(TAG, "Skipping unwritable node: $path")
            }
            return false
        }
        return writeLine(path, value.toString())
    }

    private fun resolvePolicyDirs(cpus: IntArray): List<String> {
        val resolved = mutableListOf<String>()
        cpus.forEach { cpu ->
            val cpuFreqDir = "/sys/devices/system/cpu/cpu$cpu/cpufreq"
            val canonical = runCatching { File(cpuFreqDir).canonicalPath }.getOrNull()
            val chosen = sequenceOf(
                canonical,
                "/sys/devices/system/cpu/cpufreq/policy$cpu",
                cpuFreqDir
            ).filterNotNull().firstOrNull { File(it).exists() } ?: cpuFreqDir
            resolved += chosen
        }
        return resolved.distinct()
    }

    private fun readClusterAvailableFreqs(policyDirs: List<String>, fallbackCpu: Int): List<Int> {
        val preferred = policyDirs
            .map { "$it/scaling_available_frequencies" }
            .firstOrNull { File(it).exists() }
            ?: "/sys/devices/system/cpu/cpu$fallbackCpu/cpufreq/scaling_available_frequencies"
        return readAvailableFreqs(preferred)
    }

    private fun readFreq(path: String, fallback: Int): Int =
        runCatching { File(path).readText().trim().toInt() }
            .getOrElse { e ->
                Log.w(TAG, "readFreq failed for $path, using fallback $fallback", e)
                fallback
            }

    private fun readAvailableFreqs(path: String): List<Int> =
        runCatching {
            File(path).readText()
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
                .filter { it > 0 }
                .sorted()
        }.getOrElse {
            emptyList()
        }

    private fun resolveMax(available: List<Int>, requested: Int): Int {
        if (available.isEmpty()) return requested
        return available.lastOrNull { it <= requested } ?: available.first()
    }

    private fun resolveMin(available: List<Int>, requested: Int, resolvedMax: Int): Int {
        if (available.isEmpty()) return minOf(requested, resolvedMax)
        val bounded = available.filter { it <= resolvedMax }
        if (bounded.isEmpty()) return resolvedMax
        return bounded.firstOrNull { it >= requested } ?: bounded.last()
    }
}
