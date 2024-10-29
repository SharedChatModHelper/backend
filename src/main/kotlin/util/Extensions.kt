package io.github.iprodigy.util

import com.netflix.hystrix.HystrixCommand
import java.util.NoSuchElementException
import java.util.Queue
import java.util.concurrent.BlockingQueue

// Hystrix
fun <T> HystrixCommand<T>.executeOrNull(exceptionHandler: ((Exception) -> Unit)?): T? = try {
    this.execute()
} catch (e: Exception) {
    exceptionHandler?.invoke(e)
    null
}

// Collections
fun <T : Any?, C : MutableCollection<T>> Queue<T>.drainTo(supplier: () -> C): C {
    val collection = supplier()
    require(collection != this) { "Cannot drain collection to itself!" }

    if (this is BlockingQueue) {
        this.drainTo(collection)
    } else {
        while (isNotEmpty()) {
            val e = try {
                remove()
            } catch (ignored: NoSuchElementException) {
                break
            }
            collection += e
        }
    }

    return collection
}

fun <T> Iterable<T>.ensureSet(): Set<T> = if (this is Set) this else this.toSet()
