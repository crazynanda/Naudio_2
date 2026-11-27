package com.maxrave.data.mediaservice.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap

/**
 * JNA interface for Objective-C Runtime on macOS
 * Used to call Cocoa/MediaPlayer framework APIs
 */
interface ObjCRuntime : Library {
    companion object {
        val INSTANCE: ObjCRuntime by lazy {
            Native.load("objc", ObjCRuntime::class.java)
        }
    }

    /**
     * Get a class by name
     * @param name The name of the class (e.g., "NSString", "MPNowPlayingInfoCenter")
     */
    fun objc_getClass(name: String): Pointer?

    /**
     * Register/get a selector by name
     * @param name The selector name (e.g., "alloc", "init", "defaultCenter")
     */
    fun sel_registerName(name: String): Pointer?

    /**
     * Send a message to an object (no arguments, returns Pointer)
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
    ): Pointer?

    /**
     * Send a message with one Pointer argument
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
        arg1: Pointer?,
    ): Pointer?

    /**
     * Send a message with two Pointer arguments
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
        arg1: Pointer?,
        arg2: Pointer?,
    ): Pointer?

    /**
     * Send a message with one double argument
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
        arg1: Double,
    ): Pointer?

    /**
     * Send a message with one long argument
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
        arg1: Long,
    ): Pointer?

    /**
     * Send a message with one int argument
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
        arg1: Int,
    ): Pointer?


    /**
     * Send a message with Pointer and Long arguments (for initWithBytes:length:)
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
        bytes: Pointer?,
        length: Long,
    ): Pointer?

    /**
     * Objective-C autorelease pool management (libobjc, macOS 10.7+).
     *
     * The JVM worker threads this integration runs on have NO autorelease pool, so every
     * autoreleased object a framework method hands back — most notably the object returned
     * by the `nowPlayingInfo` getter — would never be drained and would leak for the life
     * of the process. Wrap each update method in [ObjC.autoreleasePool].
     */
    fun objc_autoreleasePoolPush(): Pointer?

    fun objc_autoreleasePoolPop(pool: Pointer?)
}

/**
 * Separate interface for objc_msgSend that returns Double
 * This is needed because JNA needs different function signatures for different return types
 * Works on both ARM64 and x86_64
 */
interface ObjCRuntimeDouble : Library {
    companion object {
        val INSTANCE: ObjCRuntimeDouble by lazy {
            Native.load("objc", ObjCRuntimeDouble::class.java)
        }
    }

    /**
     * Send a message that returns a double value
     * On ARM64, this uses the regular objc_msgSend
     * JNA will handle the return type correctly
     */
    fun objc_msgSend(
        receiver: Pointer?,
        selector: Pointer?,
    ): Double
}

/**
 * JNA interface for CoreFoundation framework
 */
interface CoreFoundation : Library {
    companion object {
        val INSTANCE: CoreFoundation by lazy {
            Native.load("CoreFoundation", CoreFoundation::class.java)
        }

        // CFStringEncoding values
        const val kCFStringEncodingUTF8 = 0x08000100
    }

    /**
     * Create a CFString from a C string
     * CFString is toll-free bridged with NSString
     * @param alloc Pass null for default allocator
     * @param cStr The C string (JNA auto-converts String to const char*)
     * @param encoding Use kCFStringEncodingUTF8
     */
    fun CFStringCreateWithCString(
        alloc: Pointer?,
        cStr: String,
        encoding: Int,
    ): Pointer?

}

/**
 * Helper object for Objective-C operations
 */
object ObjC {
    private val runtime = ObjCRuntime.INSTANCE

    // Selectors and classes are process-wide singletons; the caches are filled from the main
    // thread (initialization) and JVM worker threads (every Now Playing update), so they must
    // be thread-safe.
    private val selectorCache = ConcurrentHashMap<String, Pointer?>()

    private val classCache = ConcurrentHashMap<String, Pointer?>()

    /**
     * Get a cached selector
     */
    fun sel(name: String): Pointer? =
        selectorCache.getOrPut(name) {
            runtime.sel_registerName(name)
        }

    /**
     * Get a cached class
     */
    fun cls(name: String): Pointer? =
        classCache.getOrPut(name) {
            runtime.objc_getClass(name)
        }

    /**
     * Send a message with no arguments
     */
    fun msg(
        receiver: Pointer?,
        selector: String,
    ): Pointer? = runtime.objc_msgSend(receiver, sel(selector))

    /**
     * Send a message with one Pointer argument
     */
    fun msg(
        receiver: Pointer?,
        selector: String,
        arg1: Pointer?,
    ): Pointer? = runtime.objc_msgSend(receiver, sel(selector), arg1)

    /**
     * Send a message with two Pointer arguments
     */
    fun msg(
        receiver: Pointer?,
        selector: String,
        arg1: Pointer?,
        arg2: Pointer?,
    ): Pointer? = runtime.objc_msgSend(receiver, sel(selector), arg1, arg2)

    /**
     * Send a message with one double argument
     */
    fun msg(
        receiver: Pointer?,
        selector: String,
        arg1: Double,
    ): Pointer? = runtime.objc_msgSend(receiver, sel(selector), arg1)

    /**
     * Send a message with one long argument
     */
    fun msg(
        receiver: Pointer?,
        selector: String,
        arg1: Long,
    ): Pointer? = runtime.objc_msgSend(receiver, sel(selector), arg1)

    /**
     * Send a message with one int argument
     */
    fun msg(
        receiver: Pointer?,
        selector: String,
        arg1: Int,
    ): Pointer? = runtime.objc_msgSend(receiver, sel(selector), arg1)


    /**
     * Release a Kotlin-owned (+1) Objective-C/CF object.
     *
     * Every factory below creates OWNED objects (`alloc`/`init…` or a CF `Create` function),
     * so after the framework has retained what it needs (e.g. `setObject:forKey:`), the caller
     * must drop its +1 exactly once or it leaks. Never call this on an object whose ownership
     * rule is autoreleased.
     */
    fun releaseOwned(obj: Pointer?) {
        if (obj != null) {
            runtime.objc_msgSend(obj, sel("release"))
        }
    }

    /**
     * Run [block] inside an autorelease pool.
     *
     * JVM worker threads have no autorelease pool: anything autoreleased on them — most
     * notably the object returned by the `nowPlayingInfo` getter — is never drained and leaks
     * for the life of the process. If the pool symbols are unavailable, the block still runs;
     * only the cleanup guarantee is lost.
     */
    fun autoreleasePool(block: () -> Unit) {
        val pool =
            try {
                runtime.objc_autoreleasePoolPush()
            } catch (e: Exception) {
                null
            }
        if (pool == null) {
            block()
            return
        }
        try {
            block()
        } finally {
            try {
                runtime.objc_autoreleasePoolPop(pool)
            } catch (_: Exception) {
                // A failed pop must not mask the block's own outcome.
            }
        }
    }

    /**
     * Create an NSString from a Kotlin String
     * Uses CFStringCreateWithCString which is more reliable with JNA
     * CFString is toll-free bridged with NSString
     *
     * OWNERSHIP: +1 — CF `Create` rule; the caller must [releaseOwned] after the last
     * consumer retains it.
     */
    fun nsString(str: String): Pointer? {
        // Use CoreFoundation - CFString is toll-free bridged with NSString
        return CoreFoundation.INSTANCE.CFStringCreateWithCString(
            null,
            str,
            CoreFoundation.kCFStringEncodingUTF8,
        )
    }

    /**
     * Create an NSNumber from a Double
     *
     * OWNERSHIP: +1 — `alloc`/`initWithDouble:` instead of the autoreleased `numberWithDouble:`
     * convenience constructor, so the caller can [releaseOwned] deterministically.
     */
    fun nsNumber(value: Double): Pointer? {
        val nsNumberClass = cls("NSNumber") ?: return null
        val allocated = msg(nsNumberClass, "alloc") ?: return null
        return msg(allocated, "initWithDouble:", value)
    }

    /**
     * Create an NSNumber from a Long
     *
     * OWNERSHIP: +1 — see [nsNumber] (Double).
     */
    fun nsNumber(value: Long): Pointer? {
        val nsNumberClass = cls("NSNumber") ?: return null
        val allocated = msg(nsNumberClass, "alloc") ?: return null
        return msg(allocated, "initWithLongLong:", value)
    }

    /**
     * Create an NSNumber from an Int
     *
     * OWNERSHIP: +1 — see [nsNumber] (Double).
     */
    fun nsNumber(value: Int): Pointer? {
        val nsNumberClass = cls("NSNumber") ?: return null
        val allocated = msg(nsNumberClass, "alloc") ?: return null
        return msg(allocated, "initWithInt:", value)
    }

    /**
     * Create an NSMutableDictionary
     *
     * OWNERSHIP: +1 — the caller must [releaseOwned] after the framework retains it.
     */
    fun nsMutableDictionary(): Pointer? {
        val dictClass = cls("NSMutableDictionary") ?: return null
        val allocated = msg(dictClass, "alloc") ?: return null
        return msg(allocated, "init")
    }

    /**
     * Set object in dictionary for key
     */
    fun dictionarySetObject(
        dict: Pointer?,
        obj: Pointer?,
        key: Pointer?,
    ) {
        msg(dict, "setObject:forKey:", obj, key)
    }
}