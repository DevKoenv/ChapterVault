package dev.koenv.chaptervault.core.execution

import java.io.Closeable

/**
 * Executor interface for running instructions.
 *
 * Executors handle the actual implementation of instructions:
 * - LocalExecutor: Uses FetchClient/BrowserPool directly
 * - RemoteExecutor: Sends instructions to external runners
 * - MockExecutor: Returns predefined results for testing
 *
 * This abstraction allows connectors to be agnostic about
 * how their instructions are executed.
 */
interface Executor : Closeable {

    /**
     * Execute a single instruction and return the result.
     *
     * @param instruction The instruction to execute
     * @param context Optional execution context with session info, etc.
     * @return The execution result
     */
    suspend fun execute(instruction: Instruction, context: ExecutionContext = ExecutionContext()): ExecutionResult

    /**
     * Execute multiple instructions in sequence.
     *
     * @param instructions List of instructions to execute
     * @param context Optional execution context
     * @return Map of instruction ID to result
     */
    suspend fun executeAll(
        instructions: List<Instruction>,
        context: ExecutionContext = ExecutionContext()
    ): Map<String, ExecutionResult> {
        val results = mutableMapOf<String, ExecutionResult>()
        for (instruction in instructions) {
            results[instruction.id] = execute(instruction, context)
        }
        return results
    }

    /**
     * Check if this executor supports a specific instruction type.
     */
    fun supports(instruction: Instruction): Boolean = true

    /**
     * Get executor capabilities/info.
     */
    fun getInfo(): ExecutorInfo

    /**
     * Close the executor and release resources.
     */
    override fun close()
}

/**
 * Execution context containing session and configuration info.
 */
data class ExecutionContext(
    /**
     * Session ID for maintaining state across executions.
     * Used for cookie persistence, auth tokens, etc.
     */
    val sessionId: String? = null,

    /**
     * Connector name for rate limiting and logging.
     */
    val connectorName: String? = null,

    /**
     * Default headers to apply to all HTTP requests.
     */
    val defaultHeaders: Map<String, String> = emptyMap(),

    /**
     * Whether to use browser mode (slower but handles JS).
     */
    val useBrowser: Boolean = false,

    /**
     * Custom timeout override in milliseconds.
     */
    val timeout: Long? = null,

    /**
     * Additional metadata for the execution.
     */
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Information about an executor's capabilities.
 */
data class ExecutorInfo(
    val name: String,
    val type: ExecutorType,
    val supportsBrowser: Boolean,
    val supportsParallel: Boolean,
    val maxConcurrency: Int,
    val isRemote: Boolean,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Type of executor.
 */
enum class ExecutorType {
    LOCAL,      // Runs on the same machine
    REMOTE,     // Runs on external runner
    MOCK        // Returns mock data for testing
}

/**
 * Factory for creating executors.
 */
interface ExecutorFactory {
    /**
     * Create an executor for the given context.
     */
    fun create(context: ExecutionContext = ExecutionContext()): Executor

    /**
     * Get available executor types.
     */
    fun availableTypes(): List<ExecutorType>
}
