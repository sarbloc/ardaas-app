import SwiftUI
import SwiftData
import UIKit

/// Reads a saved Ardaas in situ: the canonical segments with the personal
/// benti composed in at the bundled slot. Pushed inside the Home screen's
/// NavigationStack.
struct ReaderView: View {
    /// Bindable: the Reader's variant picker writes straight back to the
    /// record (operator decision — the switch persists, not per-session).
    @Bindable var savedArdaas: SavedArdaas

    @AppStorage("reader.showGurmukhi") private var showGurmukhi = true
    @AppStorage("reader.showTransliteration") private var showTransliteration = true
    @AppStorage("reader.showEnglish") private var showEnglish = true
    @AppStorage("reader.fontScale") private var fontScale = 1.0

    /// Loaded once on appear; `.failure` means a broken bundle (a build
    /// defect) — surfaced, never swallowed.
    @State private var loadResult: Result<ArdaasLibrary, Error>?

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
                ToolbarItem(placement: .topBarTrailing) {
                    displayMenu
                }
            }
            .onAppear {
                UIApplication.shared.isIdleTimerDisabled = true
                if loadResult == nil {
                    loadResult = Result { try ArdaasLibrary.loadBundled() }
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
        screenAvailability.visibleLayerCount(
            showGurmukhi: showGurmukhi,
            showTransliteration: showTransliteration,
            showEnglish: showEnglish
        )
    }

    /// Layers visible in the canonical segments = toggled on AND carried by
    /// this variant. Drives `segmentView`'s never-blank guard, which must
    /// ignore the benti: a benti-only English layer keeps the screen
    /// non-empty but does nothing for a segment that has no English.
    private var visibleCanonicalLayerCount: Int {
        canonicalAvailability.visibleLayerCount(
            showGurmukhi: showGurmukhi,
            showTransliteration: showTransliteration,
            showEnglish: showEnglish
        )
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
        let items = ArdaasComposer.compose(content: content, benti: savedArdaas.bentiLayers)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    switch item {
                    case .canonical(let segment):
                        segmentView(segment)
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
    private func segmentView(_ segment: ArdaasSegment) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            // `|| visibleCanonicalLayerCount == 0` is the never-blank guard:
            // if the persisted toggles leave this variant with nothing to
            // show (e.g. Gurmukhi off + only unavailable layers on),
            // Gurmukhi renders anyway.
            if showGurmukhi || visibleCanonicalLayerCount == 0 {
                Text(segment.gurmukhi)
                    .font(.system(size: 22 * fontScale))
                    .lineSpacing(8 * fontScale)
                    .foregroundStyle(Theme.parchment)
            }
            if showTransliteration, let transliteration = segment.transliteration {
                Text(transliteration)
                    .font(.system(size: 15 * fontScale))
                    .italic()
                    .lineSpacing(5 * fontScale)
                    .foregroundStyle(Theme.sand)
            }
            if showEnglish, let english = segment.english {
                Text(english)
                    .font(.system(size: 15 * fontScale, design: .serif))
                    .lineSpacing(5 * fontScale)
                    .foregroundStyle(Theme.mist)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The benti, rendered layer by layer exactly as a canonical segment is
    /// — same type sizes, colours and spacing — inside the kesri-tinted card
    /// that marks it out as the user's own words. Which layers appear is the
    /// pure decision in `BentiLayers.visibleLines`, including its never-blank
    /// fallback, so the card is never drawn empty.
    private func bentiView(_ layers: BentiLayers) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(
                layers.visibleLines(
                    showGurmukhi: showGurmukhi,
                    showTransliteration: showTransliteration,
                    showEnglish: showEnglish
                )
            ) { line in
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
                    layerPill("Transliteration", isOn: $showTransliteration)
                }
                if hasEnglish {
                    layerPill("Translation", isOn: $showEnglish)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(.bar)
        }
    }

    private func layerPill(_ title: String, isOn: Binding<Bool>) -> some View {
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
        .disabled(isLastVisibleLayer(isOn.wrappedValue))
        .accessibilityAddTraits(isOn.wrappedValue ? .isSelected : [])
    }

    // MARK: - Toolbar

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
                    .disabled(isLastVisibleLayer(showGurmukhi))
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

    /// True when `layer` is on and it is the only layer currently visible
    /// anywhere on screen (toggled on AND carried by the variant or the
    /// benti) — unchecking it would leave nothing visible, so its toggle is
    /// disabled.
    private func isLastVisibleLayer(_ layer: Bool) -> Bool {
        layer && visibleLayerCount == 1
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
        bentiTransliteration: "parivār nūṃ tandarustī ate charhdī kalā bakhsho jī."
    )
    container.mainContext.insert(sample)
    return NavigationStack {
        ReaderView(savedArdaas: sample)
    }
    .modelContainer(container)
}
