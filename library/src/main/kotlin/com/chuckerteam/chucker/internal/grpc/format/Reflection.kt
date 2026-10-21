package com.chuckerteam.chucker.internal.grpc.format

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached reflection helpers. Message classes are only known by name, because protobuf is an
 * optional dependency that Chucker never links against.
 */
internal object Reflection {
    private val subtypes = ConcurrentHashMap<Pair<Class<*>, String>, Boolean>()
    private val methods = ConcurrentHashMap<Pair<Class<*>, String>, Method>()

    /** Returns whether [clazz] extends or implements the type named [typeName]. */
    fun isSubtypeOf(
        clazz: Class<*>,
        typeName: String,
    ): Boolean = subtypes.getOrPut(clazz to typeName) { supertypes(clazz).any { it.name == typeName } }

    /** Invokes the public no-argument method [name] on [target]. */
    fun call(
        target: Any,
        name: String,
    ): Any? {
        val method =
            methods.getOrPut(target.javaClass to name) {
                // Public methods overridden in non-public classes can't be invoked otherwise.
                target.javaClass.getMethod(name).apply { runCatching { isAccessible = true } }
            }
        return method.invoke(target)
    }

    /** Returns the field [name] declared by [clazz] or one of its superclasses, made accessible. */
    fun findField(
        clazz: Class<*>,
        name: String,
    ): Field? =
        generateSequence(clazz) { it.superclass }
            .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } }
            ?.apply { isAccessible = true }

    private fun supertypes(clazz: Class<*>): Sequence<Class<*>> =
        sequence {
            val queue = ArrayDeque<Class<*>>().apply { add(clazz) }
            val seen = HashSet<Class<*>>()
            while (queue.isNotEmpty()) {
                val type = queue.removeFirst()
                if (!seen.add(type)) continue
                yield(type)
                type.superclass?.let(queue::add)
                queue.addAll(type.interfaces)
            }
        }
}
