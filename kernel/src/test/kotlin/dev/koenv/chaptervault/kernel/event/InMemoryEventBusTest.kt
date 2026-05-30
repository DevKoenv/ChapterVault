package dev.koenv.chaptervault.kernel.event

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class TestEvent(
    val value: String,
) : DomainEvent()

private data class OtherEvent(
    val value: String,
) : DomainEvent()

class InMemoryEventBusTest {
    private val bus = InMemoryEventBus()

    @Test
    fun `global subscriber receives published event`() {
        runTest {
            val received = mutableListOf<DomainEvent>()
            bus.subscribe { received.add(it) }

            bus.publish(TestEvent("hello"))

            assertEquals(1, received.size)
            assertEquals(TestEvent("hello"), received[0])
        }
    }

    @Test
    fun `typed subscriber receives only events of its registered type`() {
        runTest {
            val received = mutableListOf<TestEvent>()
            bus.subscribe(TestEvent::class.java) { received.add(it) }

            bus.publish(TestEvent("match"))

            assertEquals(1, received.size)
            assertEquals(TestEvent("match"), received[0])
        }
    }

    @Test
    fun `typed subscriber does NOT receive events of a different type`() {
        runTest {
            val received = mutableListOf<TestEvent>()
            bus.subscribe(TestEvent::class.java) { received.add(it) }

            bus.publish(OtherEvent("ignored"))

            assertTrue(received.isEmpty())
        }
    }

    @Test
    fun `multiple global subscribers all receive the same event`() {
        runTest {
            val first = mutableListOf<DomainEvent>()
            val second = mutableListOf<DomainEvent>()
            bus.subscribe { first.add(it) }
            bus.subscribe { second.add(it) }

            bus.publish(TestEvent("broadcast"))

            assertEquals(1, first.size)
            assertEquals(1, second.size)
            assertEquals(TestEvent("broadcast"), first[0])
            assertEquals(TestEvent("broadcast"), second[0])
        }
    }

    @Test
    fun `multiple typed subscribers for the same type all receive the event`() {
        runTest {
            val first = mutableListOf<TestEvent>()
            val second = mutableListOf<TestEvent>()
            bus.subscribe(TestEvent::class.java) { first.add(it) }
            bus.subscribe(TestEvent::class.java) { second.add(it) }

            bus.publish(TestEvent("typed-broadcast"))

            assertEquals(1, first.size)
            assertEquals(1, second.size)
        }
    }

    @Test
    fun `no subscribers publish completes without error`() {
        runTest {
            bus.publish(TestEvent("nobody-listening"))
        }
    }

    @Test
    fun `global subscriber and typed subscriber both fire for a matching event`() {
        runTest {
            val globalReceived = mutableListOf<DomainEvent>()
            val typedReceived = mutableListOf<TestEvent>()
            bus.subscribe { globalReceived.add(it) }
            bus.subscribe(TestEvent::class.java) { typedReceived.add(it) }

            bus.publish(TestEvent("both"))

            assertEquals(1, globalReceived.size)
            assertEquals(1, typedReceived.size)
        }
    }
}
