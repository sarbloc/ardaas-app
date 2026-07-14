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

    /// Layers visible right now = toggled on AND present in this variant.
    private var visibleLayerCount: Int {
        var count = showGurmukhi ? 1 : 0
        if showTransliteration, loadedContent?.hasTransliteration ?? true { count += 1 }
        if showEnglish, loadedContent?.hasEnglish ?? true { count += 1 }
        return count
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
        let items = ArdaasComposer.compose(content: content, benti: savedArdaas.bentiText)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    switch item {
                    case .canonical(let segment):
                        segmentView(segment)
                    case .benti(let text):
                        bentiView(text)
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
            // `|| visibleLayerCount == 0` is the never-blank guard: if the
            // persisted toggles leave this variant with nothing to show
            // (e.g. Gurmukhi off + only unavailable layers on), Gurmukhi
            // renders anyway.
            if showGurmukhi || visibleLayerCount == 0 {
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

    private func bentiView(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 17 * fontScale))
            .lineSpacing(6 * fontScale)
            .foregroundStyle(Theme.parchment)
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

    // MARK: - Footer

    /// Quick, independent toggles for the two secondary layers. Gurmukhi's
    /// toggle stays in the toolbar menu — it's the anchor layer, switched
    /// rarely.
    @ViewBuilder
    private var footerBar: some View {
        let hasTransliteration = loadedContent?.hasTransliteration ?? false
        let hasEnglish = loadedContent?.hasEnglish ?? false
        // Pills only for layers this variant actually carries; no footer
        // at all for a gurmukhi-only variant.
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
            Label("Display Options", systemImage: "textformat.size")
        }
    }

    /// True when `layer` is on and it is the only layer currently
    /// visible (toggled on AND available in this variant) — unchecking it
    /// would leave nothing visible, so its toggle is disabled.
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
        bentiText: "Please bless the family with health and chardi kala."
    )
    container.mainContext.insert(sample)
    return NavigationStack {
        ReaderView(savedArdaas: sample)
    }
    .modelContainer(container)
}
