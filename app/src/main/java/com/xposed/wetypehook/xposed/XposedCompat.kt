package com.xposed.wetypehook.xposed

import android.util.Log as AndroidLog
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private data class ClassCacheKey(
    val name: String,
    val classLoader: ClassLoader?
)

private data class FieldCacheKey(
    val owner: Class<*>,
    val name: String
)

private data class InvokeMethodCacheKey(
    val owner: Class<*>,
    val name: String,
    val isStatic: Boolean,
    val argumentSignature: List<String?>
)

object HookEnvironment {
    @Volatile
    private var currentClassLoader: ClassLoader? = null

    @Volatile
    private var currentLogTag: String = "xposed"

    @Volatile
    private var currentModule: XposedModule? = null

    private val hookHandles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    private val hookSequenceLock = Any()
    private val hookSequences = HashMap<String, Int>()
    private val currentScope = ThreadLocal.withInitial { "global" }
    private val trackedCallbacks = Collections.synchronizedMap(
        WeakHashMap<View, MutableSet<Runnable>>()
    )

    /**
     * Attaches the current module generation to the compatibility layer.
     *
     * The framework owns the XposedInterface instance exposed by XposedModule. Keeping the
     * module reference here lets the legacy-shaped helper functions below retain their call-site
     * API while all actual registrations go through libxposed's interceptor chain.
     */
    fun attach(module: XposedModule, classLoader: ClassLoader? = null, logTag: String = "xposed") {
        currentModule = module
        currentClassLoader = classLoader
        currentLogTag = logTag
        synchronized(hookSequenceLock) {
            hookSequences.clear()
        }
        currentScope.set("global")
        cancelTrackedCallbacks()
    }

    fun updateClassLoader(classLoader: ClassLoader?) {
        currentClassLoader = classLoader
    }

    /**
     * Kept as a source-compatible bridge while callers move to the modern lifecycle callbacks.
     * Hook registration itself still requires [attach] to have supplied the module instance.
     */
    @Deprecated("Use attach(XposedModule, ClassLoader?, String) from onModuleLoaded")
    fun init(classLoader: ClassLoader?, logTag: String) {
        currentClassLoader = classLoader
        currentLogTag = logTag
    }

    internal fun resolveClassLoader(explicitClassLoader: ClassLoader?): ClassLoader? =
        explicitClassLoader ?: currentClassLoader

    internal fun logTag(): String = currentLogTag

    internal fun moduleOrNull(): XposedModule? = currentModule

    fun <T> withHookScope(scope: String, block: () -> T): T {
        val previous = currentScope.get()
        currentScope.set(scope.ifBlank { "global" })
        return try {
            block()
        } finally {
            currentScope.set(previous)
        }
    }

    fun prepareForHotReload(oldHandles: List<XposedInterface.HookHandle>? = null) {
        cancelTrackedCallbacks()
        currentScope.set("global")
        synchronized(hookSequenceLock) {
            hookSequences.clear()
        }
    }

    fun finishHotReload(oldHandles: List<XposedInterface.HookHandle>) {
        oldHandles.forEach { handle ->
            runCatching { handle.unhook() }
        }
    }

    /**
     * Posts a callback that is cancelled automatically by [cancelTrackedCallbacks] during hot
     * reload. This prevents old-generation lambdas from remaining in a target View's message
     * queue and retaining the old module classloader.
     */
    fun postTracked(view: View, delayMillis: Long = 0L, block: () -> Unit): Boolean {
        lateinit var callback: Runnable
        callback = Runnable {
            try {
                block()
            } finally {
                synchronized(trackedCallbacks) {
                    trackedCallbacks[view]?.let { callbacks ->
                        callbacks.remove(callback)
                        if (callbacks.isEmpty()) trackedCallbacks.remove(view)
                    }
                }
            }
        }

        synchronized(trackedCallbacks) {
            trackedCallbacks.getOrPut(view) { LinkedHashSet() }.add(callback)
        }
        val posted = if (delayMillis > 0L) {
            view.postDelayed(callback, delayMillis)
        } else {
            view.post(callback)
        }
        if (!posted) {
            synchronized(trackedCallbacks) {
                trackedCallbacks[view]?.let { callbacks ->
                    callbacks.remove(callback)
                    if (callbacks.isEmpty()) trackedCallbacks.remove(view)
                }
            }
        }
        return posted
    }

    fun cancelTrackedCallbacks() {
        val pending = synchronized(trackedCallbacks) {
            trackedCallbacks.entries.flatMap { (view, callbacks) ->
                callbacks.map { callback -> view to callback }
            }.also { trackedCallbacks.clear() }
        }
        pending.forEach { (view, callback) ->
            runCatching { view.removeCallbacks(callback) }
        }
    }

    internal fun registerHook(
        method: Method,
        kind: String,
        hooker: XposedInterface.Hooker
    ): XposedInterface.HookHandle {
        val module = currentModule
            ?: throw IllegalStateException("XposedModule is not attached before hooking ${method.name}")
        val id = nextHookId(method, kind)
        return module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId(id)
            .intercept(hooker)
            .also(hookHandles::add)
    }

    private fun nextHookId(method: Method, kind: String): String {
        val scope = currentScope.get().ifBlank { "global" }
        val executableKey = buildString {
            append(method.declaringClass.name)
            append('#')
            append(method.name)
            append('(')
            append(method.parameterTypes.joinToString(",") { it.name })
            append(')')
            append(':')
            append(method.returnType.name)
        }
        val sequenceKey = "$scope|$kind|$executableKey"
        val sequence = synchronized(hookSequenceLock) {
            val next = hookSequences[sequenceKey] ?: 0
            hookSequences[sequenceKey] = next + 1
            next
        }
        return "api102:$scope:$kind:$executableKey:$sequence"
    }
}

object Log {
    fun i(message: Any?) {
        log("I", message)
    }

    fun e(message: Any?) {
        log("E", message)
    }

    private fun log(level: String, message: Any?) {
        val priority = if (level == "E") AndroidLog.ERROR else AndroidLog.INFO
        val module = HookEnvironment.moduleOrNull()
        if (message is Throwable) {
            val text = "[${HookEnvironment.logTag()}][$level] ${message.message ?: message.javaClass.name}"
            if (module != null) {
                module.log(priority, HookEnvironment.logTag(), text, message)
            } else {
                AndroidLog.println(priority, HookEnvironment.logTag(), text)
            }
            return
        }
        val text = "[${HookEnvironment.logTag()}][$level] ${message ?: "null"}"
        if (module != null) {
            module.log(priority, HookEnvironment.logTag(), text)
        } else {
            AndroidLog.println(priority, HookEnvironment.logTag(), text)
        }
    }
}

private val primitiveToWrapper = mapOf(
    Boolean::class.javaPrimitiveType to Boolean::class.javaObjectType,
    Byte::class.javaPrimitiveType to Byte::class.javaObjectType,
    Char::class.javaPrimitiveType to Char::class.javaObjectType,
    Double::class.javaPrimitiveType to Double::class.javaObjectType,
    Float::class.javaPrimitiveType to Float::class.javaObjectType,
    Int::class.javaPrimitiveType to Int::class.javaObjectType,
    Long::class.javaPrimitiveType to Long::class.javaObjectType,
    Short::class.javaPrimitiveType to Short::class.javaObjectType,
    Void.TYPE to Void::class.java
)

private fun boxed(clazz: Class<*>): Class<*> = primitiveToWrapper[clazz] ?: clazz

private val classCache = ConcurrentHashMap<ClassCacheKey, Class<*>>()
private val fieldCache = ConcurrentHashMap<FieldCacheKey, Field>()
private val invokeMethodCache = ConcurrentHashMap<InvokeMethodCacheKey, Method>()

fun loadClassOrNull(className: String, classLoader: ClassLoader? = null): Class<*>? {
    val resolvedClassLoader = HookEnvironment.resolveClassLoader(classLoader)
    val key = ClassCacheKey(className, resolvedClassLoader)
    classCache[key]?.let { return it }

    val loadedClass = runCatching {
        Class.forName(className, false, resolvedClassLoader)
    }.getOrNull()
    if (loadedClass != null) {
        classCache[key] = loadedClass
    }
    return loadedClass
}

fun findMethod(
    className: String,
    classLoader: ClassLoader? = null,
    predicate: Method.() -> Boolean
): Method {
    val owner = loadClassOrNull(className, classLoader)
        ?: throw ClassNotFoundException("Class not found: $className")
    return owner.findMethod(predicate)
}

fun Class<*>.findMethod(predicate: Method.() -> Boolean): Method {
    declaredMethods.firstOrNull(predicate)?.let { method ->
        method.isAccessible = true
        return method
    }
    throw NoSuchMethodException("No method matched in ${name}")
}

fun Class<*>.findMethodInHierarchy(predicate: Method.() -> Boolean): Method {
    var searchClass: Class<*>? = this
    while (searchClass != null) {
        searchClass.declaredMethods.firstOrNull(predicate)?.let { method ->
            method.isAccessible = true
            return method
        }
        searchClass = searchClass.superclass
    }
    throw NoSuchMethodException("No method matched in hierarchy of ${name}")
}

fun Array<Class<*>>.sameAs(vararg types: Class<*>): Boolean {
    if (size != types.size) return false
    return indices.all { index ->
        boxed(this[index]) == boxed(types[index])
    }
}

private object StaticHookThisObject

class MethodHookParam internal constructor(
    val method: Method,
    thisObject: Any?,
    args: Array<Any?>,
    result: Any? = null
) {
    /**
     * Existing call sites treat the hooked receiver as non-null. Keep that source shape for
     * instance hooks; static hooks receive an internal sentinel and ignore this field.
     */
    val thisObject: Any = thisObject ?: StaticHookThisObject

    var args: Array<Any?> = args

    var result: Any? = result
        set(value) {
            field = value
            resultWasSet = true
        }

    internal var resultWasSet: Boolean = false
}

fun Method.hookBefore(callback: (MethodHookParam) -> Unit) {
    val method = this
    HookEnvironment.registerHook(method, "before") { chain ->
        val param = MethodHookParam(
            method = method,
            thisObject = chain.thisObject,
            args = chain.args.toTypedArray()
        )
        val callbackResult = runCatching { callback(param) }
        val callbackFailure = callbackResult.exceptionOrNull()
        if (callbackFailure != null) {
            Log.e(callbackFailure)
            chain.proceed(param.args)
        } else if (param.resultWasSet) {
            param.result
        } else {
            chain.proceed(param.args)
        }
    }
}

fun Method.hookAfter(callback: (MethodHookParam) -> Unit) {
    val method = this
    HookEnvironment.registerHook(method, "after") { chain ->
        val originalResult = chain.proceed()
        val param = MethodHookParam(
            method = method,
            thisObject = chain.thisObject,
            args = chain.args.toTypedArray(),
            result = originalResult
        )
        try {
            callback(param)
            param.result
        } catch (throwable: Throwable) {
            Log.e(throwable)
            originalResult
        }
    }
}

fun Method.hookReplace(callback: (MethodHookParam) -> Any?) {
    val method = this
    HookEnvironment.registerHook(method, "replace") { chain ->
        val param = MethodHookParam(
            method = method,
            thisObject = chain.thisObject,
            args = chain.args.toTypedArray()
        )
        runCatching { callback(param) }.getOrElse { throwable ->
            Log.e(throwable)
            chain.proceed(param.args)
        }
    }
}

fun Method.hookReturnConstant(result: Any?) {
    val method = this
    HookEnvironment.registerHook(method, "constant") { result }
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.getObjectAs(fieldName: String): T? = findField(javaClass, fieldName).get(this) as? T

fun Class<*>.getStaticObject(fieldName: String): Any? = findField(this, fieldName).get(null)

fun Class<*>.putStaticObject(fieldName: String, value: Any?) {
    findField(this, fieldName).set(null, value)
}

@Suppress("UNCHECKED_CAST")
fun <T> Any.invokeMethodAs(methodName: String, vararg args: Any?): T? =
    findCompatibleMethod(javaClass, methodName, isStatic = false, args = args).invoke(this, *args) as? T

fun Class<*>.invokeStaticMethodAuto(methodName: String, vararg args: Any?): Any? =
    findCompatibleMethod(this, methodName, isStatic = true, args = args).invoke(null, *args)

private fun findField(owner: Class<*>, fieldName: String): Field {
    val key = FieldCacheKey(owner, fieldName)
    return fieldCache.getOrPut(key) {
        var searchClass: Class<*>? = owner
        while (searchClass != null) {
            runCatching {
                searchClass.getDeclaredField(fieldName)
            }.getOrNull()?.let { field ->
                field.isAccessible = true
                return@getOrPut field
            }
            searchClass = searchClass.superclass
        }
        throw NoSuchFieldException("${owner.name}#$fieldName")
    }
}

private fun findCompatibleMethod(
    owner: Class<*>,
    methodName: String,
    isStatic: Boolean,
    args: Array<out Any?>
): Method {
    val key = InvokeMethodCacheKey(
        owner = owner,
        name = methodName,
        isStatic = isStatic,
        argumentSignature = args.map { argument -> argument?.javaClass?.name }
    )
    return invokeMethodCache.getOrPut(key) {
        resolveCompatibleMethod(owner, methodName, isStatic, args)
    }
}

private fun resolveCompatibleMethod(
    owner: Class<*>,
    methodName: String,
    isStatic: Boolean,
    args: Array<out Any?>
): Method {
    var searchClass: Class<*>? = owner
    while (searchClass != null) {
        searchClass.declaredMethods.firstOrNull { method ->
            method.name == methodName &&
                Modifier.isStatic(method.modifiers) == isStatic &&
                method.parameterTypes.size == args.size &&
                method.parameterTypes.indices.all { index ->
                    isCompatibleArgument(method.parameterTypes[index], args[index])
                }
        }?.let { method ->
            method.isAccessible = true
            return method
        }
        searchClass = searchClass.superclass
    }

    owner.methods.firstOrNull { method ->
        method.name == methodName &&
            Modifier.isStatic(method.modifiers) == isStatic &&
            method.parameterTypes.size == args.size &&
            method.parameterTypes.indices.all { index ->
                isCompatibleArgument(method.parameterTypes[index], args[index])
            }
    }?.let { method ->
        method.isAccessible = true
        return method
    }

    throw NoSuchMethodException("${owner.name}#$methodName(${args.joinToString { it?.javaClass?.name ?: "null" }})")
}

private fun isCompatibleArgument(parameterType: Class<*>, argument: Any?): Boolean {
    if (argument == null) return !parameterType.isPrimitive
    return boxed(parameterType).isAssignableFrom(boxed(argument.javaClass))
}
