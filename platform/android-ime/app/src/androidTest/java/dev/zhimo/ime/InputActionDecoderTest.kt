// Copyright © 2026 立方田 <managecode@gmail.com>
package dev.zhimo.ime

import org.junit.Assert.*
import org.junit.Test

class InputActionDecoderTest {
    @Test fun returnIsExplicitCommitAndClose() {
        assertEquals(listOf(InputAction.Commit("jixu"), InputAction.Close),
            InputActionDecoder.decode("""[{"CommitText":"jixu"},"CloseComposition"]"""))
    }
    @Test fun compositionDoesNotBecomeCommit() {
        assertEquals(listOf(InputAction.Composition("nihao")), InputActionDecoder.decode(
            """[{"UpdateComposition":{"segments":[{"text":"ni"},{"text":"hao"}]}}]"""))
    }
    @Test fun unknownActionsAndLegacyReadingsAreCompatible() {
        for (kind in listOf("PinyinReadings", "ReadingOptions")) {
            assertEquals(listOf(InputAction.Readings(listOf("lian"), null)),
                InputActionDecoder.decode("""["Ignored",{"FutureAction":{}},{"$kind":{"readings":["lian"],"selected":null}}]"""))
        }
    }
    @Test fun malformedBatchCannotPartiallyCommit() {
        assertTrue(runCatching { InputActionDecoder.decode(
            """[{"CommitText":"hello"},{"UpdateComposition":{}}]""") }.isFailure)
    }
}
