import SwiftUI
import SwiftData
import UIKit

/// Reads a saved Ardaas in situ: the canonical segments with the personal
/// benti composed in at the bundled slot and the chosen occasion spliced
/// into the "….." (#67). Pushed inside the Home screen's NavigationStack.
struct ReaderView: View {
    /// Bindable: the Reader's variant picker writes straight back to the
    /// record (operator decision — the switch persists, not per-session).
    /// The occasion picker in `OccasionEditorSheet` persists the same way.
    @Bindable var savedArdaas: SavedArdaas

    @AppStorage("reader.showGurmukhi") private var showGurmukhi = true
    @AppStorage("reader.showTransliteration") private var showTransliteration = true
    @AppStorage("reader.showEnglish") private var showEnglish = true
    @AppStorage("reader.fontScale") private var fontScale = 1.0

    /// Loaded once on appear; `.failure` means a broken bundle (a build
    /// defect) — surfaced, never swallowed.
    @State private var loadResult: Result<ArdaasLibrary, Error>?

    /// Loaded once on appear. Failure leaves the "….." exactly as authored
    /// and hides the occasion picker — the Ardaas still reads, which is what
    /// this screen is for. The scripture's own load failure is the one that
    /// takes the screen (above).
    @State private var occasionCatalog: OccasionCatalog?

    @State private var isEditingOccasion = false

    private static let fontScaleRange = 0.7...1.6
    private static let fontScaleStep = 0.1

    init(savedArdaas: SavedArdaas) {
        self.savedArdaas = savedArdaas
    }

    var body: some View {
        content
            .navigationTitle(savedArdaas.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Its own control, ahead of the gear: what the prayer is for
                // is content, not a display preference, and burying it in a
                // menu of layer toggles and text size would say otherwise.
                if canEditOccasion {
                    ToolbarItem(placement: .topBarTrailing) {
                        occasionButton
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    displayMenu
                }
            }
            .sheet(isPresented: $isEditingOccasion) {
                if let occasionCatalog {
                    OccasionEditorSheet(
                        savedArdaas: savedArdaas,
                        catalog: occasionCatalog,
                        content: loadedContent
                    )
                }
            }
            .onAppear {
                UIApplication.shared.isIdleTimerDisabled = true
                if loadResult == nil {
                    loadResult = Result { try ArdaasLibrary.loadBundled() }
                }
                if occasionCatalog == nil {
                    occasionCatalog = try? OccasionCatalog.loadBundled()
                }
                // Heal records whose variant was removed from the bundle:
                // rewrite to the default so the stored id, the rendered
                // content, and the picker selection all agree.
                if case .success(let library) = loadResult,
                   library.variant(id: savedArdaas.variantId) == nil {
                    savedArdaas.variantId = library.defaultVariant.id
                }
            }
            .onDisappear {
                UIApplication.shared.isIdleTimerDisabled = false
            }
    }

    /// The resolved variant's content once loading has succeeded.
    private var loadedContent: ArdaasContent? {
        guard case .success(let library) = loadResult else { return nil }
        return library.resolvedVariant(id: savedArdaas.variantId).content
    }

    /// The persisted toggles as one value, so the visibility rules can be
    /// pure functions of it.
    private var toggles: LayerToggles {
        LayerToggles(
            gurmukhi: showGurmukhi,
            transliteration: showTransliteration,
            english: showEnglish
        )
    }

    /// What the canonical segments can show — the variant's own coverage.
    private var canonicalAvailability: LayerAvailability {
        .canonical(loadedContent)
    }

    /// What the screen as a whole can show — the variant's coverage plus the
    /// benti's own populated layers (see `LayerAvailability.screen`).
    private var screenAvailability: LayerAvailability {
        .screen(content: loadedContent, benti: savedArdaas.bentiLayers)
    }

    /// Layers visible anywhere on screen = toggled on AND carried by the
    /// variant or the benti. Drives which toggles are disabled, so the last
    /// thing on screen can never be switched off.
    private var visibleLayerCount: Int {
        screenAvailability.visibleLayerCount(under: toggles)
    }

    @ViewBuilder
    private var content: some View {
        switch loadResult {
        case nil:
            ProgressView()
        case .success:
            if let loadedContent {
                reader(for: loadedContent)
            }
        case .failure(let error):
            ContentUnavailableView(
                "Ardaas Text Unavailable",
                systemImage: "exclamationmark.triangle",
                description: Text(String(describing: error))
            )
        }
    }

    private func reader(for content: ArdaasContent) -> some View {
        // The whole composition — benti and occasion both — is one call on
        // the record, so what this screen renders is exactly what the tests
        // assert (`SavedArdaas.renderItems(in:catalog:)`).
        let items = savedArdaas.renderItems(in: content, catalog: occasionCatalog)
        let occasionSlotId = content.occasionSlot?.inSegmentId
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    switch item {
                    case .canonical(let segment):
                        segmentView(segment, isOccasionSlot: segment.id == occasionSlotId)
                    case .benti(let layers):
                        bentiView(layers)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .themedScreen()
        .safeAreaInset(edge: .bottom) {
            footerBar
        }
    }

    // MARK: - Rows

    @ViewBuilder
    private func segmentView(_ segment: ArdaasSegment, isOccasionSlot: Bool) -> some View {
        // `renderedLayers` carries the never-blank guard: if the persisted
        // toggles leave this variant with nothing to show (e.g. Gurmukhi off
        // + only layers it lacks on), Gurmukhi renders anyway. It reasons
        // about the variant alone, never the benti — a benti-only English
        // layer keeps the screen non-empty but does nothing for a segment
        // that has no English.
        let rendered = canonicalAvailability.renderedLayers(under: toggles)
        VStack(alignment: .leading, spacing: 6) {
            if rendered.contains(.gurmukhi) {
                Text(segment.gurmukhi)
                    .font(.system(size: 22 * fontScale))
                    .lineSpacing(8 * fontScale)
                    .foregroundStyle(Theme.parchment)
            }
            if rendered.contains(.transliteration), let transliteration = segment.transliteration {
                Text(transliteration)
                    .font(.system(size: 15 * fontScale))
                    .italic()
                    .lineSpacing(5 * fontScale)
                    .foregroundStyle(Theme.sand)
            }
            if rendered.contains(.english), let english = segment.english {
                Text(english)
                    .font(.system(size: 15 * fontScale, design: .serif))
                    .lineSpacing(5 * fontScale)
                    .foregroundStyle(Theme.mist)
            }
            if isOccasionSlot, occasionWillNotAppear {
                strandedOccasionNote
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The chosen occasion lands in no layer this variant carries, so the
    /// reader is looking at the dots and would have no idea why — see
    /// `OccasionChoice.willNotAppear(in:catalog:)` and #72.
    ///
    /// Compose warns about this while choosing, but the Reader can *create*
    /// it: switching variants moves free text onto an Ardaas that cannot show
    /// it. Same condition and same words as the picker, said at the slot
    /// itself, and it opens the picker so it can be fixed where it is read.
    private var occasionWillNotAppear: Bool {
        guard let loadedContent, let occasionCatalog else { return false }
        return savedArdaas.occasionChoice.willNotAppear(in: loadedContent, catalog: occasionCatalog)
    }

    private var strandedOccasionNote: some View {
        Button {
            isEditingOccasion = true
        } label: {
            Label(OccasionCopy.willNotAppear, systemImage: "exclamationmark.triangle")
                .font(.footnote)
                .foregroundStyle(Theme.kesri)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .padding(.top, 4)
        .accessibilityHint("Opens the occasion picker")
    }

    /// The benti, rendered layer by layer exactly as a canonical segment is
    /// — same type sizes, colours and spacing — inside the kesri-tinted card
    /// that marks it out as the user's own words. Which layers appear is the
    /// pure decision in `BentiLayers.visibleLines`, including its never-blank
    /// fallback, so the card is never drawn empty.
    private func bentiView(_ layers: BentiLayers) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(layers.visibleLines(under: toggles)) { line in
                bentiLine(line)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            Color.accentColor.opacity(0.12),
            in: RoundedRectangle(cornerRadius: 10)
        )
        .overlay(alignment: .leading) {
            RoundedRectangle(cornerRadius: 2)
                .fill(Color.accentColor.opacity(0.6))
                .frame(width: 3)
                .padding(.vertical, 6)
        }
    }

    /// One benti layer, styled to match its canonical counterpart in
    /// `segmentView` so the two read as one continuous text.
    @ViewBuilder
    private func bentiLine(_ line: BentiLine) -> some View {
        switch line.kind {
        case .gurmukhi:
            Text(line.text)
                .font(.system(size: 22 * fontScale))
                .lineSpacing(8 * fontScale)
                .foregroundStyle(Theme.parchment)
        case .transliteration:
            Text(line.text)
                .font(.system(size: 15 * fontScale))
                .italic()
                .lineSpacing(5 * fontScale)
                .foregroundStyle(Theme.sand)
        case .english:
            Text(line.text)
                .font(.system(size: 15 * fontScale, design: .serif))
                .lineSpacing(5 * fontScale)
                .foregroundStyle(Theme.mist)
        }
    }

    // MARK: - Footer

    /// Quick, independent toggles for the two secondary layers. Gurmukhi's
    /// toggle stays in the toolbar menu — it's the anchor layer, switched
    /// rarely.
    @ViewBuilder
    private var footerBar: some View {
        // Pills only for layers something on screen actually carries — the
        // variant or the benti. Using the screen-wide availability (not the
        // variant's) is what keeps the user's own words controllable on a
        // variant with no attested English: the benti's English renders
        // there, so its pill has to be there to switch it off. No footer at
        // all when neither the variant nor the benti has either layer.
        let availability = screenAvailability
        let hasTransliteration = availability.transliteration
        let hasEnglish = availability.english
        if hasTransliteration || hasEnglish {
            HStack(spacing: 12) {
                if hasTransliteration {
                    layerPill("Transliteration", kind: .transliteration, isOn: $showTransliteration)
                }
                if hasEnglish {
                    layerPill("Translation", kind: .english, isOn: $showEnglish)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(.bar)
        }
    }

    private func layerPill(_ title: String, kind: LayerKind, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
        } label: {
            Text(title)
                .font(.subheadline.weight(.medium))
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(
                    isOn.wrappedValue ? Color.accentColor.opacity(0.15) : .clear,
                    in: Capsule()
                )
                .overlay(
                    Capsule().strokeBorder(
                        isOn.wrappedValue ? Color.accentColor : Color.secondary.opacity(0.4),
                        lineWidth: 1
                    )
                )
                .foregroundStyle(isOn.wrappedValue ? Color.accentColor : Color.secondary)
        }
        .buttonStyle(.plain)
        .disabled(isPinnedLayer(kind))
        .accessibilityAddTraits(isOn.wrappedValue ? .isSelected : [])
    }

    // MARK: - Toolbar

    /// Only when there is something to pick *and* somewhere to put it: a
    /// catalog that loaded, and a variant that declares an occasion slot. A
    /// picker that could never change a word would be a lie.
    private var canEditOccasion: Bool {
        occasionCatalog != nil && loadedContent?.occasionSlot != nil
    }

    /// Opens the occasion picker. Kept out of `displayMenu` — that menu is
    /// display preferences (which layers, how big); this changes the words of
    /// the Ardaas itself, and it is the one thing on this screen a reader may
    /// want to change between two recitations.
    private var occasionButton: some View {
        Button {
            isEditingOccasion = true
        } label: {
            Label("Occasion", systemImage: "tag")
        }
    }

    private var displayMenu: some View {
        Menu {
            if case .success(let library) = loadResult, library.variants.count > 1 {
                Section("Ardaas") {
                    Picker("Ardaas", selection: $savedArdaas.variantId) {
                        ForEach(library.variants) { variant in
                            Text(variant.displayName).tag(variant.id)
                        }
                    }
                }
            }
            Section("Layers") {
                Toggle("Gurmukhi", isOn: $showGurmukhi)
                    .disabled(isPinnedLayer(.gurmukhi))
            }
            Section("Text Size") {
                Button {
                    adjustFontScale(by: Self.fontScaleStep)
                } label: {
                    Label("Larger Text", systemImage: "textformat.size.larger")
                }
                .disabled(fontScale >= Self.fontScaleRange.upperBound)

                Button {
                    adjustFontScale(by: -Self.fontScaleStep)
                } label: {
                    Label("Smaller Text", systemImage: "textformat.size.smaller")
                }
                .disabled(fontScale <= Self.fontScaleRange.lowerBound)
            }
        } label: {
            Label("Display Options", systemImage: "gearshape")
        }
    }

    /// True when `kind`'s toggle is on but switching it off would achieve
    /// nothing, so the control is disabled rather than left as a dead one.
    /// Two ways that happens:
    ///
    /// - It is the only layer visible anywhere on screen (toggled on AND
    ///   carried by the variant or the benti). Unchecking it would leave
    ///   nothing the reader asked for; the segments' never-blank guard is a
    ///   backstop, not a destination.
    /// - Neither the segments nor the benti would drop it, because a
    ///   never-blank rule puts it straight back: the segments' guard
    ///   restores Gurmukhi on a variant that carries nothing else, and the
    ///   benti's fallback restores the single written layer of a benti still
    ///   awaiting translation. Without this the Translation pill this PR
    ///   newly offers on a variant with no attested English would be a no-op
    ///   for exactly the pending-translation case.
    private func isPinnedLayer(_ kind: LayerKind) -> Bool {
        guard toggles[kind] else { return false }
        if visibleLayerCount == 1 { return true }
        return !canonicalAvailability.hidesLayer(kind, under: toggles)
            && !savedArdaas.bentiLayers.hidesLayer(kind, under: toggles)
    }

    private func adjustFontScale(by delta: Double) {
        let next = ((fontScale + delta) * 10).rounded() / 10
        fontScale = min(max(next, Self.fontScaleRange.lowerBound), Self.fontScaleRange.upperBound)
    }
}

#Preview {
    let container = try! ModelContainer(
        for: SavedArdaas.self,
        configurations: ModelConfiguration(isStoredInMemoryOnly: true)
    )
    let sample = SavedArdaas(
        label: "Morning Ardaas",
        bentiText: "Please bless the family with health and chardi kala.",
        bentiGurmukhi: "ਪਰਿਵਾਰ ਨੂੰ ਤੰਦਰੁਸਤੀ ਅਤੇ ਚੜ੍ਹਦੀ ਕਲਾ ਬਖ਼ਸ਼ੋ ਜੀ।",
        bentiTransliteration: "parivār nūṃ tandarustī ate charhdī kalā bakhsho jī.",
        occasion: .catalog(id: "japji-sahib")
    )
    container.mainContext.insert(sample)
    return NavigationStack {
        ReaderView(savedArdaas: sample)
    }
    .modelContainer(container)
}
