package com.beust.kobalt.internal

import com.beust.kobalt.KobaltException
import com.beust.kobalt.TaskResult
import com.beust.kobalt.misc.NamedThreadFactory
import com.beust.kobalt.misc.error
import com.beust.kobalt.misc.log
import com.google.common.collect.HashMultimap
import java.lang.reflect.InvocationTargetException
import java.util.*
import java.util.concurrent.*

open class TaskResult2<T>(success: Boolean, errorMessage: String?, val value: T) : TaskResult(success, errorMessage) {
    override fun toString() = com.beust.kobalt.misc.toString("TaskResult", "value", value, "success", success)
}

class Node<T>(val value: T) {
    override fun hashCode() = value!!.hashCode()
    override fun equals(other: Any?) : Boolean {
        val result = if (other is Node<*>) other.value == value else false
        return result
    }
    override fun toString() = value.toString()
}

class DynamicGraph<T> {
    val VERBOSE = 2
    val values : Collection<T> get() = nodes.map { it.value }
    val nodes = hashSetOf<Node<T>>()
    private val dependedUpon = HashMultimap.create<Node<T>, Node<T>>()
    private val dependingOn = HashMultimap.create<Node<T>, Node<T>>()

    fun addNode(t: T) = synchronized(nodes) {
        nodes.add(Node(t))
    }

    fun removeNode(t: T) = synchronized(nodes) {
        log(VERBOSE, "  Removing node $t")
        Node(t).let { node ->
            nodes.remove(node)
            dependingOn.removeAll(node)
            val set = dependedUpon.keySet()
            val toReplace = arrayListOf<Pair<Node<T>, Collection<Node<T>>>>()
            set.forEach { du ->
                val l = ArrayList(dependedUpon[du])
                l.remove(node)
                toReplace.add(Pair(du, l))
            }
            toReplace.forEach {
                dependedUpon.replaceValues(it.first, it.second)
            }
        }
    }

    /**
     * Make "from" depend on "to" ("from" is no longer free).
     */
    fun addEdge(from: T, to: T) {
        val fromNode = Node(from)
        nodes.add(fromNode)
        val toNode = Node(to)
        nodes.add(Node(to))
        dependingOn.put(toNode, fromNode)
        dependedUpon.put(fromNode, toNode)
    }

    val freeNodes: Set<T>
        get() {
            val nonFree = hashSetOf<T>()
            synchronized(nodes) {
                nodes.forEach {
                    val du = dependedUpon[it]
                    if (du != null && du.size > 0) {
                        nonFree.add(it.value)
                    }
                }
                val result = nodes.map { it.value }.filter { !nonFree.contains(it) }.toHashSet()
                log(VERBOSE, "  Free nodes: $result")
                return result
            }
        }

    fun dump() : String {
        val result = StringBuffer()
        result.append("************ Graph dump ***************\n")
        val free = arrayListOf<Node<T>>()
        nodes.forEach { node ->
            val d = dependedUpon.get(node)
            if (d == null || d.isEmpty()) {
                free.add(node)
            }
        }

        result.append("All nodes: $values\n").append("Free nodes: $free").append("\nDependent nodes:\n")
        nodes.forEach {
            val deps = dependedUpon.get(it)
            if (! deps.isEmpty()) {
                result.append("     $it -> $deps\n")
            }
        }
        return result.toString()
    }
}

interface IWorker<T> : Callable<TaskResult2<T>> {
    /**
     * @return list of tasks this worker is working on.
     */
    //    val tasks : List<T>

    /**
     * @return the priority of this task.
     */
    val priority : Int
}

interface IThreadWorkerFactory<T> {

    /**
     * Creates {@code IWorker} for specified set of tasks. It is not necessary that
     * number of workers returned be same as number of tasks entered.
     *
     * @param nodes tasks that need to be executed
     * @return list of workers
     */
    fun createWorkers(nodes: Collection<T>) : List<IWorker<T>>
}

class DynamicGraphExecutor<T>(val graph : DynamicGraph<T>, val factory: IThreadWorkerFactory<T>) {
    val executor = Executors.newFixedThreadPool(5, NamedThreadFactory("DynamicGraphExecutor"))
    val completion = ExecutorCompletionService<TaskResult2<T>>(executor)

    fun run() : TaskResult {
        try {
            return run2()
        } finally {
            executor.shutdown()
        }
    }

    private fun run2() : TaskResult {
        var running = 0
        val nodesRun = hashSetOf<T>()
        var failedResult: TaskResult? = null
        val newFreeNodes = HashSet<T>(graph.freeNodes)
        while (failedResult == null && (running > 0 || newFreeNodes.size > 0)) {
            nodesRun.addAll(newFreeNodes)
            val callables : List<IWorker<T>> = factory.createWorkers(newFreeNodes)
            callables.forEach { completion.submit(it) }
            running += callables.size

            try {
                val future = completion.take()
                val taskResult = future.get(2, TimeUnit.SECONDS)
                running--
                if (taskResult.success) {
                    nodesRun.add(taskResult.value)
                    log(2, "Task succeeded: $taskResult")
                    graph.removeNode(taskResult.value)
                    newFreeNodes.clear()
                    newFreeNodes.addAll(graph.freeNodes.minus(nodesRun))
                } else {
                    log(2, "Task failed: $taskResult")
                    newFreeNodes.clear()
                    if (failedResult == null) {
                        failedResult = taskResult
                    }
                }
            } catch(ex: TimeoutException) {
                log(2, "Time out")
            } catch(ex: Exception) {
                val ite = ex.cause
                if (ite is InvocationTargetException) {
                    if (ite.targetException is KobaltException) {
                        throw (ex.cause as InvocationTargetException).targetException
                    } else {
                        error("Error: ${ite.cause?.message}", ite.cause)
                        failedResult = TaskResult(success = false, errorMessage = ite.cause?.message)
                    }
                } else {
                    error("Error: ${ex.message}", ex)
                    failedResult = TaskResult(success = false, errorMessage = ex.message)
                }
            }
        }
        return if (failedResult != null) failedResult else TaskResult()
    }
}

fun main(argv: Array<String>) {
    val dg = DynamicGraph<String>().apply {
        // a -> b
        // b -> c, d
        // e
        addEdge("a", "b")
        addEdge("b", "c")
        addEdge("b", "d")
        addNode("e")
    }
    val factory = object : IThreadWorkerFactory<String> {
        override fun createWorkers(nodes: Collection<String>): List<IWorker<String>> {
            return nodes.map {
                object: IWorker<String> {
                    override fun call(): TaskResult2<String>? {
                        log(1, "  Running worker $it")
                        return TaskResult2(true, null, it)
                    }

                    override val priority: Int get() = 0
                }
            }
        }
    }

    DynamicGraphExecutor(dg, factory).run()
}
