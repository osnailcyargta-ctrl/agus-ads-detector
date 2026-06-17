package com.agus.adsdetector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdDetectorService : AccessibilityService() {

    companion object {
        // Ad SDK package name patterns
        val AD_PACKAGES = listOf(
            "com.google.android.gms.ads",
            "com.facebook.ads",
            "com.unity3d.ads",
            "com.applovin",
            "com.chartboost",
            "com.vungle",
            "com.ironsource",
            "com.mopub",
            "com.startapp",
            "air.com.imangi",
        )

        // Keywords to look for in UI text (loaded from user settings)
        var activeKeywords: List<String> = listOf(
            "skip ad", "close ad", "watch ad", "skip", "lewati",
            "iklan", "advertisement", "admob", "tutup iklan"
        )

        private var lastDetectedTime = 0L
        private const val COOLDOWN_MS = 5000L // Don't spam — 5s cooldown per detection
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Only process window changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        // Check cooldown
        val now = System.currentTimeMillis()
        if (now - lastDetectedTime < COOLDOWN_MS) return

        val packageName = event.packageName?.toString() ?: return

        // Skip our own app
        if (packageName == "com.agus.adsdetector") return

        // Method 1: Package name contains known ad SDK pattern
        val isAdPackage = AD_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
        if (isAdPackage) {
            triggerAdDetected(packageName, "Ad SDK package")
            return
        }

        // Method 2: Scan visible UI text for ad keywords
        val rootNode = rootInActiveWindow ?: return
        val foundKeyword = findAdKeywordInNode(rootNode)
        rootNode.recycle()

        if (foundKeyword != null) {
            triggerAdDetected(packageName, foundKeyword)
        }
    }

    private fun findAdKeywordInNode(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (keyword in activeKeywords) {
            if (text.contains(keyword) || contentDesc.contains(keyword)) {
                return keyword
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findAdKeywordInNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun triggerAdDetected(packageName: String, trigger: String) {
        lastDetectedTime = System.currentTimeMillis()

        // Get app name from package
        val appName = try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }

        MainActivity.instance?.addLog("🚨 Ad detected: $appName [$trigger]")

        // Send via Bluetooth
        BluetoothService.instance?.sendAdDetected(appName, trigger)
    }

    override fun onInterrupt() {}
}
