package dev.chaptervault.shared.utils

import java.util.UUID

@JvmInline
value class Id(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun generate(): Id = Id(UUID.randomUUID())
        fun from(value: String): Id = Id(UUID.fromString(value))
    }
}
