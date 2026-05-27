package me.gm.cleaner.dao

import me.gm.cleaner.annotation.App
import me.gm.cleaner.annotation.Server

object PurchaseVerification {

    // Cleanup
    @App
    inline val isCleanupPro: Boolean
        get() = true

    // CleanerService
    @App
    inline val isExpressPro: Boolean
        get() = true

    @App
    inline val isStrictPro: Boolean
        get() = true

    @App
    @Server
    inline val isLoosePro: Boolean
        get() = true
}
