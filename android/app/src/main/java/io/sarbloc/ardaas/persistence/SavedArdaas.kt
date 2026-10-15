package io.sarbloc.ardaas.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import io.sarbloc.ardaas.composer.ArdaasComposer
import io.sarbloc.ardaas.composer.BentiLayers
import io.sarbloc.ardaas.composer.RenderItem
import io.sarbloc.ardaas.content.ArdaasContent
import io.sarbloc.ardaas.content.ArdaasLibrary
import io.sarbloc.ardaas.occasion.OccasionCatalog
import io.sarbloc.ardaas.occasion.OccasionChoice
import io.sarbloc.ardaas.occasion.OccasionLayers
import java.time.Instant
import java.time.temporal.ChronoUnit

// Port of Ardaas/Models/SavedArdaas.swift: the stored properties as the Room
// entity, and its computed extensions below it.

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
     * through [OccasionChoice] — the `occasion` constructor and
     * [withOccasionChoice] — which keeps it from holding an impossible
     * combination such as an id with a stale free-text draft. The raw
     * parameters exist for Room and `copy`, as iOS's stored properties stay
     * public for SwiftData.
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
) {
    /**
     * A new record whose occasion is written as a consistent pair through the
     * choice — iOS's `init(…, occasion:)`. [occasion] has no default, so a
     * call without it can only mean the primary constructor. Ignored by Room,
     * which reads rows through the primary constructor.
     */
    @Ignore
    constructor(
        label: String,
        bentiText: String,
        occasion: OccasionChoice,
        createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        variantId: String = ArdaasLibrary.DEFAULT_VARIANT_ID,
        bentiGurmukhi: String = "",
        bentiTransliteration: String = "",
    ) : this(
        label = label,
        bentiText = bentiText,
        createdAt = createdAt,
        variantId = variantId,
        bentiGurmukhi = bentiGurmukhi,
        bentiTransliteration = bentiTransliteration,
        occasionId = occasion.storedId,
        occasionCustom = occasion.storedCustomText,
    )
}

// Computed views of the stored fields. Extensions rather than members so Room
// never has to decide whether they are columns.

/**
 * Clarity alias for [SavedArdaas.bentiText]: the user's own words, which
 * serve as the benti's English/translation layer.
 */
val SavedArdaas.bentiEnglish: String get() = bentiText

/**
 * This record with a new English layer. The entity is immutable, so iOS's
 * `bentiEnglish` setter becomes a copy.
 */
fun SavedArdaas.withBentiEnglish(text: String): SavedArdaas = copy(bentiText = text)

/** The two persisted occasion fields as the one value the pickers and the composer reason about. */
val SavedArdaas.occasionChoice: OccasionChoice
    get() = OccasionChoice.fromStored(occasionId = occasionId, occasionCustom = occasionCustom)

/**
 * This record with a new occasion — iOS's `occasionChoice` setter. Writes
 * both fields together so the pair stays consistent: in particular it clears
 * [SavedArdaas.occasionCustom] for any choice that is not free text.
 */
fun SavedArdaas.withOccasionChoice(choice: OccasionChoice): SavedArdaas =
    copy(occasionId = choice.storedId, occasionCustom = choice.storedCustomText)

/** The persisted layers as the value type the composer and Reader use. */
val SavedArdaas.bentiLayers: BentiLayers
    get() = BentiLayers(gurmukhi = bentiGurmukhi, transliteration = bentiTransliteration, english = bentiText)

/**
 * The chosen occasion's words, resolved through the bundled catalog.
 *
 * Null — the canonical dots, untouched — for nothing chosen and for an id a
 * later bundle has retired ([OccasionChoice.layers]). A broken bundle must
 * cost the reader the substitution, never the Ardaas.
 *
 * A **free-text** occasion is stored whole on this record and needs no
 * catalog, so it survives a catalog that failed to load (a null [catalog]).
 * Dropping the user's own words because an unrelated bundled file is missing
 * would be the wrong trade.
 */
fun SavedArdaas.occasionLayers(catalog: OccasionCatalog?): OccasionLayers? {
    val choice = occasionChoice
    if (catalog == null) {
        return if (choice is OccasionChoice.Custom) OccasionLayers.fromFreeText(choice.text) else null
    }
    return choice.layers(catalog)
}

/**
 * Exactly what the Reader draws: this record's benti composed into [content],
 * with its chosen occasion spliced into the "….." slot.
 *
 * The Reader calls nothing else, so this one function is the whole
 * composition path and the tests that cover it cannot drift from the screen.
 */
fun SavedArdaas.renderItems(content: ArdaasContent, catalog: OccasionCatalog?): List<RenderItem> =
    ArdaasComposer.compose(content = content, benti = bentiLayers, occasion = occasionLayers(catalog))
