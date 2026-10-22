import Foundation

/// The "….." slot's two independent choices (#158): the **paath** that was
/// recited, and the **occasion** the Ardaas marks. Either, both or neither.
///
/// `SavedArdaas` persists the paath as `paathId` — `""` for none, otherwise a
/// bundled entry whose category is `.paath` — and the occasion as it always
/// has, through `OccasionChoice` (`occasionId` + `occasionCustom`). A paath
/// is never free text: "Other…" belongs to the occasion.
///
/// ## Legacy records and the normaliser
///
/// Before #158 there was one picker, so a paath was stored in `occasionId`.
/// Those records are read, not migrated: `normalised(in:)` applies these
/// rules at read time, so no data migration ever needs the catalog.
///
/// 1. `paathId` empty and `occasionId` naming a catalog entry whose category
///    is `.paath` → that entry **is the paath**, and the occasion is none.
/// 2. `paathId` naming an entry that is not a paath, or one the catalog does
///    not carry (retired) → **ignored**: no paath, the same way a retired
///    occasion renders the canonical dots.
/// 3. `paathId` set *and* `occasionId` naming a paath → the occasion is
///    **ignored** (it cannot be both, and the explicit field wins).
/// 4. Everything else is left exactly as stored — including an occasion id
///    the catalog has retired, which resolves to no words just as before.
///
/// The next save writes the normalised form (`paathId` set, `occasionId`
/// cleared), because every writer goes through a draft opened on it.
struct SlotChoice: Equatable {
    /// `SavedArdaas.paathId`: empty for no paath, else a bundled paath's id.
    var paathId: String = ""
    var occasion: OccasionChoice = .unset

    /// The rules above, applied against `catalog`. Pure and total; applying
    /// it twice changes nothing more.
    func normalised(in catalog: OccasionCatalog) -> SlotChoice {
        let storedPaath = paathId.trimmingCharacters(in: .whitespacesAndNewlines)
        let legacyPaath: String? = {
            guard case .catalog(let id) = occasion,
                  catalog.occasion(id: id)?.category == .paath else { return nil }
            return id
        }()

        // Rule 1: the pre-#158 shape.
        if storedPaath.isEmpty {
            if let legacyPaath { return SlotChoice(paathId: legacyPaath, occasion: .unset) }
            return SlotChoice(paathId: "", occasion: occasion)
        }
        // Rule 2: only a real paath counts as one.
        let paath = catalog.occasion(id: storedPaath)?.category == .paath ? storedPaath : ""
        // Rule 3: a paath in the occasion field beside an explicit paath.
        return SlotChoice(paathId: paath, occasion: legacyPaath == nil ? occasion : .unset)
    }

    /// The words for the slot, or nil when nothing should be substituted and
    /// the dots stay exactly as authored. Normalises first, so a legacy
    /// record renders its paath and an ignored id renders nothing.
    ///
    /// Paath and occasion are joined per layer by
    /// `OccasionLayers.joining(paath:occasion:joiner:)`.
    func layers(in catalog: OccasionCatalog) -> OccasionLayers? {
        let normal = normalised(in: catalog)
        let paath = catalog.occasion(id: normal.paathId).map {
            OccasionLayers(gurmukhi: $0.gurmukhi, transliteration: $0.transliteration, english: $0.english)
        }
        return OccasionLayers.joining(
            paath: paath,
            occasion: normal.occasion.layers(in: catalog),
            joiner: catalog.joiner
        )
    }
}

extension OccasionLayers {
    /// One slot phrase from a paath and an occasion, layer by layer (#158):
    ///
    /// * neither → nil (the dots, untouched);
    /// * one → exactly that one, so a single choice reads as it always has;
    /// * both → `<paath> <joiner> <occasion>`, e.g. `ਸੁਖਮਨੀ ਸਾਹਿਬ ਦੇ ਪਾਠ ਅਤੇ
    ///   ਜਨਮ ਦਿਹਾੜੇ`.
    ///
    /// Per layer, because free text fills only one script
    /// (`init(freeText:)`): where the occasion has no words for a layer,
    /// that layer reads the paath alone, and vice versa. A missing joiner —
    /// or one with an empty layer — falls back to the paath alone rather than
    /// gluing two phrases together or failing.
    static func joining(
        paath: OccasionLayers?,
        occasion: OccasionLayers?,
        joiner: SlotJoiner?
    ) -> OccasionLayers? {
        guard let paath, !paath.isEmpty else {
            guard let occasion, !occasion.isEmpty else { return nil }
            return occasion
        }
        guard let occasion, !occasion.isEmpty else { return paath }
        func join(_ first: String, _ second: String, _ word: String?) -> String {
            if first.isEmpty { return second }
            if second.isEmpty { return first }
            let word = word?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return word.isEmpty ? first : "\(first) \(word) \(second)"
        }
        return OccasionLayers(
            gurmukhi: join(paath.gurmukhi, occasion.gurmukhi, joiner?.gurmukhi),
            transliteration: join(paath.transliteration, occasion.transliteration, joiner?.transliteration),
            english: join(paath.english, occasion.english, joiner?.english)
        )
    }
}
