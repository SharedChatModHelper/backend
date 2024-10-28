package io.github.iprodigy.util

import java.util.ArrayDeque
import java.util.Deque
import java.util.Queue
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.function.BiConsumer
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val ADDING_TO_HEAD = false
private const val ADDING_TO_TAIL = true

interface DrainableQueue<E> : Queue<E>, MutableCollection<E> {
    fun drain(): Collection<E> = drainTo { ArrayDeque() }
}

interface DrainableDeque<E> : Deque<E>, DrainableQueue<E>

class ConcurrentBoundedDeque<E : Any>(private val fixedCapacity: Int, fair: Boolean = false, private val policy: RemovalPolicy = RemovalPolicy.OPPOSITE) : DrainableDeque<E> {
    private val lock = ReentrantReadWriteLock(fair)
    private val queue = ArrayDeque<E>(fixedCapacity)

    override val size: Int
        get() = lock.read { queue.size }

    override fun add(element: E) = offerLast(element)

    override fun addAll(elements: Collection<E>) = elements.isNotEmpty() && lock.write {
        val currentSize = queue.size
        val addCount = elements.size
        val removeCount = currentSize + addCount - fixedCapacity
        if (removeCount > 0) {
            if (removeCount >= currentSize) {
                queue.clear()
            } else {
                repeat(removeCount) {
                    policy.accept(queue, ADDING_TO_TAIL)
                }
            }
        }

        val skipCount = maxOf(addCount - fixedCapacity, 0)
        queue.addAll(elements.let { if (skipCount > 0) it.drop(skipCount) else it })
    }

    override fun clear() = lock.write { queue.clear() }

    override fun remove(): E = removeFirst()

    override fun contains(element: E) = lock.read { queue.contains(element) }

    override fun containsAll(elements: Collection<E>) = lock.read { queue.containsAll(elements) }

    override fun isEmpty() = lock.read { queue.isEmpty() }

    override fun remove(element: E) = lock.write { queue.remove(element) }

    override fun removeAll(elements: Collection<E>) = elements.isNotEmpty() && elements.ensureSet().let {
        lock.write {
            queue.removeAll(it)
        }
    }

    override fun retainAll(elements: Collection<E>): Boolean = if (this.isEmpty()) false else lock.write {
        if (queue.isEmpty()) {
            false
        } else if (elements.isEmpty()) {
            val hadElements = this.isNotEmpty()
            if (hadElements) queue.clear()
            hadElements
        } else {
            elements.ensureSet().let {
                queue.retainAll(it)
            }
        }
    }

    override fun offer(e: E) = offerLast(e)

    override fun poll(): E? = pollFirst()

    override fun element(): E = this.first

    override fun peek(): E? = peekFirst()

    override fun addFirst(e: E) = lock.write {
        if (full()) policy.accept(queue, ADDING_TO_HEAD)
        queue.addFirst(e)
    }

    override fun addLast(e: E) = lock.write {
        if (full()) policy.accept(queue, ADDING_TO_TAIL)
        queue.addLast(e)
    }

    override fun offerFirst(e: E): Boolean {
        addFirst(e)
        return true
    }

    override fun offerLast(e: E): Boolean {
        addLast(e)
        return true
    }

    override fun removeFirst(): E = lock.write { queue.removeFirst() }

    override fun removeLast(): E = lock.write { queue.removeLast() }

    override fun pollFirst(): E? = lock.write { queue.pollFirst() }

    override fun pollLast(): E? = lock.write { queue.pollLast() }

    override fun getFirst(): E = lock.read { queue.first() }

    override fun getLast(): E = lock.read { queue.last() }

    override fun peekFirst(): E? = lock.read { queue.firstOrNull() }

    override fun peekLast(): E? = lock.read { queue.lastOrNull() }

    override fun removeFirstOccurrence(o: Any?) = lock.write { queue.remove(o) }

    override fun removeLastOccurrence(o: Any?): Boolean = lock.write { queue.removeLastOccurrence(o) }

    override fun push(e: E) = addFirst(e)

    override fun pop(): E = removeFirst()

    override fun drain(): Collection<E> = if (isEmpty()) emptyList() else lock.write {
        val n = queue.size
        if (n == 0) return@write emptyList()

        val q = ArrayDeque<E>(n)
        while (queue.isNotEmpty()) {
            q += queue.poll()
        }
        q
    }

    override fun iterator(): MutableIterator<E> = SnapshotIterator()

    override fun descendingIterator(): MutableIterator<E> = SnapshotIterator(lock.read { queue.toMutableList() }.apply { reverse() })

    override fun toString(): String = lock.read { "ConcurrentBoundedDeque(underlying=$queue)" }

    private fun full() = queue.size == fixedCapacity

    enum class RemovalPolicy : BiConsumer<Deque<out Any?>, Boolean> {
        OPPOSITE {
            override fun accept(t: Deque<out Any?>, u: Boolean) {
                // analogous to FIFO
                if (u == ADDING_TO_TAIL) {
                    t.pollFirst()
                } else {
                    t.pollLast()
                }
            }
        },
        SAME {
            override fun accept(t: Deque<out Any?>, u: Boolean) {
                // analogous to LIFO
                if (u == ADDING_TO_HEAD) {
                    t.pollFirst()
                } else {
                    t.pollLast()
                }
            }
        },
        HEAD {
            override fun accept(t: Deque<out Any?>, u: Boolean) {
                // always remove from head
                t.pollFirst()
            }
        },
        TAIL {
            override fun accept(t: Deque<out Any?>, u: Boolean) {
                // always remove from tail
                t.pollLast()
            }
        }
    }

    private inner class SnapshotIterator(private val it: MutableIterator<E>) : MutableIterator<E> by it {
        private var last: E? = null

        constructor(mutableCollection: MutableCollection<E>) : this(mutableCollection.iterator())
        constructor() : this(lock.read { queue.toMutableList() })

        override fun next(): E {
            val e = it.next()
            last = e
            return e
        }

        override fun remove() {
            val current = last

            it.remove()

            if (current != null) {
                lock.write {
                    queue.remove(current) // note: this will remove first occurrence as the specific index may have changed
                }
            }

            last = null
        }
    }
}
