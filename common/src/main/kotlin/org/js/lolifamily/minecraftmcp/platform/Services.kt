package org.js.lolifamily.minecraftmcp.platform

import org.js.lolifamily.minecraftmcp.Constants
import org.js.lolifamily.minecraftmcp.platform.services.IPlatformHelper
import java.util.ServiceLoader

object Services {

    val PLATFORM: IPlatformHelper = load(IPlatformHelper::class.java)

    fun <T> load(clazz: Class<T>): T {
        // Explicit loader, not the default TCCL: on a plugin host that is the server's loader — the PARENT of
        // the jar we live in, which therefore cannot see our services file. The interface's own loader can.
        val loadedService = ServiceLoader.load(clazz, clazz.classLoader).findFirst()
            .orElseThrow { NullPointerException("Failed to load service for ${clazz.name}") }
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz)
        return loadedService
    }
}
