import SwiftUI
import SwiftData
import UIKit

/// Reads a saved Ardaas in situ: the canonical segments with the personal
/// benti composed in at the bundled slot. Pushed inside the Home screen's
/// NavigationStack.
struct ReaderView: View {
    let savedArdaas: SavedArdaas

    @AppStorage("reader.showGurmukhi") private var showGurmukhi = true
    @AppStorage("reader.showTransliteration") private var showTransliteration = true
    @AppStorage("reader.showEnglish") private var showEnglish = true
    @AppStorage("reader.fontScale") private var fontScale = 1.0

    /// Loaded once on appear; `.failure` means a broken bundle (a build
    /// defect) — surfaced, never swallowed.
    @State private var loadResult: Result<ArdaasContent, Error>?

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
                    loadResult = Result { try ArdaasContent.loadBundled() }
                }
            }
            .onDisappear {
                UIApplication.shared.isIdleTimerDisabled = false
            }
    }

    @ViewBuilder
    private var content: some View {
        switch loadResult {
        case nil:
            ProgressView()
        case .success(let content):
            reader(for: content)
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
            if showGurmukhi {
                Text(segment.gurmukhi)
                    .font(.system(size: 22 * fontScale))
                    .lineSpacing(8 * fontScale)
                    .foregroundStyle(Theme.parchment)
            }
            if showTransliteration {
                Text(segment.transliteration)
                    .font(.system(size: 15 * fontScale))
                    .italic()
                    .lineSpacing(5 * fontScale)
                    .foregroundStyle(Theme.sand)
            }
            if showEnglish {
                Text(segment.english)
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
    private var footerBar: some View {
        HStack(spacing: 12) {
            layerPill("Transliteration", isOn: $showTransliteration)
            layerPill("Translation", isOn: $showEnglish)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(.bar)
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

    /// True when `layer` is on and the other two are off — unchecking it
    /// would leave nothing visible, so its toggle is disabled.
    private func isLastVisibleLayer(_ layer: Bool) -> Bool {
        layer && [showGurmukhi, showTransliteration, showEnglish].filter { $0 }.count == 1
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
