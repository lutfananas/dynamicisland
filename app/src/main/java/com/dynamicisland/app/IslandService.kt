package com.dynamicisland.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent

/**
 * Layanan aksesibilitas dipakai agar island bisa digambar sebagai
 * TYPE_ACCESSIBILITY_OVERLAY: tampil di atas status bar & layar kunci
 * tanpa izin "tampil di atas aplikasi lain".
 */
class IslandService : AccessibilityService() {
    private var island: IslandWindow? = null

    override fun onServiceConnected() {
        island?.detach()
        island = IslandWindow(this).also { it.attach() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        island?.onConfigChanged(newConfig)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        island?.detach()
        island = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        island?.detach()
        island = null
        super.onDestroy()
    }
}
