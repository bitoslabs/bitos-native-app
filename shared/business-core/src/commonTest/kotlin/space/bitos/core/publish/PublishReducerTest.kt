package space.bitos.core.publish

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import space.bitos.core.model.MediaHash

class PublishReducerTest {
    @Test
    fun signingCannotStartBeforeRemoteVerification() {
        val hash = requireNotNull(MediaHash.parse("a".repeat(64)))
        val draft = PublishState()

        val invalid = PublishReducer.reduce(draft, PublishAction.RemoteVerified)
        assertEquals(PublishStage.DRAFT, invalid.state.stage)

        val validating = PublishReducer.reduce(draft, PublishAction.Validate)
        assertEquals(PublishStage.VALIDATING, validating.state.stage)
        assertIs<PublishEffect.ValidateProject>(validating.effects.single())

        val rendering = PublishReducer.reduce(validating.state, PublishAction.ValidationPassed(hash))
        assertEquals(PublishStage.RENDERING, rendering.state.stage)
    }
}
