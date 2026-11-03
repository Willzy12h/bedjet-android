package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimpleViewDefaultTest {
    @Test fun `new installations start in Simple view`() {
        assertTrue(Settings().simpleView)
    }

    @Test fun `a saved curve view choice survives loading`() {
        assertFalse(Json.decodeFromString(Settings.serializer(), """{"simpleView":false}""").simpleView)
    }

    @Test fun `a saved Simple view choice survives loading`() {
        assertTrue(Json.decodeFromString(Settings.serializer(), """{"simpleView":true}""").simpleView)
    }
}
