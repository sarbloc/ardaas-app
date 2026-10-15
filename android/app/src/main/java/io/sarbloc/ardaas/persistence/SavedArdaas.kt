package io.sarbloc.ardaas.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.sarbloc.ardaas.content.ArdaasLibrary
import java.time.Instant
import java.time.temporal.ChronoUnit

// Port of the stored properties of Ardaas/Models/SavedArdaas.swift. The
// computed extensions (bentiEnglish, occasionChoice, bentiLayers,
// occasionLayers, renderItems) need the composer and occasion types and land
// with #85.

/**
 * A user-saved Ardaas: a labelled personal benti composed into the canonical
 * text at render time. Only the benti is persisted — the canonical segments
 * always come from the bundled content.
 *
 * The benti carries the same three layers as a canonical segment
 * (Gurmukhi / transliteration / English). All three are stored even when the
 * chosen variant renders only some of them, so switching variants never
 * discards work.
 *
 * Field names and semantics match iOS exactly, so a future export/import or
 * cross-device feature does not have to translate between the two apps.
 *
 * Column defaults repeat the Kotlin defaults in the schema itself, so a row
 * written without them — or a column added by a later migration — reads the
 * same as a record built here.
 */
@Entity(tableName = "saved_ardaas")
data class SavedArdaas(
    val label: String,
    /**
     * The English (translation) layer — the text the user typed.
     *
     * Named `bentiText`, not `bentiEnglish`, to match iOS, where the name
     * predates the three-layer benti and renaming the stored property would
     * break SwiftData's lightweight migration.
     */
    val bentiText: String,
    /**
     * Stored as epoch milliseconds (see [Converters]), so anything finer is
     * dropped on save. The default is truncated to match, so a fresh record
     * equals what is read back.
     */
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    /**
     * Which Ardaas variant this benti composes into. Defaults to the standard
     * SGPC text — the pinned default, not the manifest's first entry.
     */
    @ColumnInfo(defaultValue = ArdaasLibrary.DEFAULT_VARIANT_ID)
    val variantId: String = ArdaasLibrary.DEFAULT_VARIANT_ID,
    /** The Gurmukhi layer of the benti — empty until it is translated. */
    @ColumnInfo(defaultValue = "")
    val bentiGurmukhi: String = "",
    /** The Roman transliteration of [bentiGurmukhi] — empty until it is generated. */
    @ColumnInfo(defaultValue = "")
    val bentiTransliteration: String = "",
    /**
     * Which entry fills the Ardaas's "….." slot: a bundled occasion's id, the
     * custom sentinel id when the user typed their own, or **empty for
     * "nothing chosen"**, which renders the canonical dots.
     *
     * Together with [occasionCustom] this pair is meant to be written only
     * through `OccasionChoice` (#85), which keeps it from holding an
     * impossible combination such as an id with a stale free-text draft.
     */
    @ColumnInfo(defaultValue = "")
    val occasionId: String = "",
    /**
     * The user's own words for the slot, used only when [occasionId] is the
     * custom sentinel. Free text has no id and cannot come from the bundle, so
     * it is stored verbatim and in its own field.
     */
    @ColumnInfo(defaultValue = "")
    val occasionCustom: String = "",
    /**
     * Row id, assigned by SQLite on insert; leave it 0 for a new record.
     * AUTOINCREMENT, so a deleted record's id is never handed to a new one —
     * a stale reference finds nothing rather than someone else's Ardaas.
     */
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
)
