import Foundation

/// One of the three display layers the Reader renders, for a canonical
/// segment and for the user's benti alike.
enum LayerKind: Hashable {
    case gurmukhi
    case transliteration
    case english
}

/// One line of the benti as it should be rendered: which layer it is (the
/// Reader styles each differently) and the text to show.
struct BentiLine: Equatable, Identifiable {
    let kind: LayerKind
    let text: String

    /// A benti contributes at most one line per layer, so the layer is the
    /// stable identity.
    var id: LayerKind { kind }
}

extension BentiLayers {
    /// The benti's lines to render, in layer order, under the Reader's
    /// layer toggles.
    ///
    /// Rules:
    /// 1. Only *populated* layers are candidates — an empty layer means
    ///    "not written yet" (see `BentiLayers`), so a benti awaiting
    ///    translation shows only what exists.
    /// 2. A candidate renders when its toggle is on.
    /// 3. Never blank: if the toggles would leave a benti that *has*
    ///    content with nothing to show, the first populated layer renders
    ///    anyway — the same principle as the canonical segments' guard. A
    ///    visible-but-empty benti card is worse than an unrequested layer.
    ///
    /// Deliberately takes no variant coverage. `ArdaasContent.hasEnglish` /
    /// `hasTransliteration` describe whether the *scripture* has an
    /// attested layer; the benti is the user's own words, which carry no
    /// attestation question and must never be hidden by one (operator
    /// decision, #45). So the benti's English renders on a variant whose
    /// canonical text has no English — see `LayerAvailability.screen`,
    /// which is what keeps that layer's toggle reachable.
    ///
    /// An all-blank benti yields no lines; the composer never emits one
    /// (`ArdaasComposer.compose` drops it), so the Reader never draws an
    /// empty card.
    func visibleLines(
        showGurmukhi: Bool,
        showTransliteration: Bool,
        showEnglish: Bool
    ) -> [BentiLine] {
        let populated: [(line: BentiLine, isOn: Bool)] = [
            (line: BentiLine(kind: .gurmukhi, text: gurmukhi), isOn: showGurmukhi),
            (line: BentiLine(kind: .transliteration, text: transliteration), isOn: showTransliteration),
            (line: BentiLine(kind: .english, text: english), isOn: showEnglish),
        ].filter { !$0.line.text.isEmpty }

        let toggledOn = populated.filter(\.isOn).map(\.line)
        guard toggledOn.isEmpty else { return toggledOn }
        return populated.first.map { [$0.line] } ?? []
    }
}

/// Which of the two optional layers can appear at all, for some part of the
/// Reader. Gurmukhi is not modelled: every variant carries it and the benti
/// is only ever missing it before translation, so it is always offered.
///
/// Two scopes, because the variant and the benti can disagree about a layer:
/// - `canonical` — what the scripture carries; the segments' never-blank
///   guard has to reason about this alone, or a variant without English
///   would render blank segments while the benti still showed its own.
/// - `screen` — the union with the benti's populated layers; this drives
///   the layer *controls*, so a layer only the benti carries is still
///   switchable and a layer nothing carries can't be the "last visible" one.
struct LayerAvailability: Equatable {
    let transliteration: Bool
    let english: Bool

    /// The variant's own coverage. A nil `content` is the pre-load frame:
    /// assume full coverage so no toggle is disabled before we know.
    static func canonical(_ content: ArdaasContent?) -> LayerAvailability {
        LayerAvailability(
            transliteration: content?.hasTransliteration ?? true,
            english: content?.hasEnglish ?? true
        )
    }

    /// The variant's coverage ∪ the benti's populated layers — everything
    /// the screen can show. This is the operator decision from #45 made
    /// operational: because the benti renders its English regardless of the
    /// variant's attestation, the English control must be reachable
    /// regardless too, or the user could neither hide nor restore their own
    /// words on a variant like Buddha Dal (Gurmukhi + transliteration, no
    /// attested English).
    static func screen(content: ArdaasContent?, benti: BentiLayers) -> LayerAvailability {
        let variant = canonical(content)
        return LayerAvailability(
            transliteration: variant.transliteration || !benti.transliteration.isEmpty,
            english: variant.english || !benti.english.isEmpty
        )
    }

    /// How many layers are visible in this scope: toggled on *and*
    /// available. Gurmukhi counts whenever its toggle is on.
    func visibleLayerCount(
        showGurmukhi: Bool,
        showTransliteration: Bool,
        showEnglish: Bool
    ) -> Int {
        var count = showGurmukhi ? 1 : 0
        if showTransliteration, transliteration { count += 1 }
        if showEnglish, english { count += 1 }
        return count
    }
}
