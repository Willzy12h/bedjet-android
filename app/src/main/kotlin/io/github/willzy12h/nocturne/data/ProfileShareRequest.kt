package io.github.willzy12h.nocturne.data

import kotlinx.serialization.Serializable

/** Saved with the document-picker request; neither a later edit nor a session change can substitute its data. */
@Serializable
internal data class ProfileShareRequest(val encodedProfile: String, val sessionRevision: Long)
