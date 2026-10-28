import SwiftUI
import SwiftData
import UIKit
import os

/// Reads a saved Ardaas in situ: the canonical segments with the personal
/// benti composed in at the bundled slot and the chosen occasion spliced
/// into the "….." (#67). Pushed inside the Home screen's NavigationStack.
struct ReaderView: View {
    /// Bindable: the Reader's variant picker writes straight back to the
    /// record (operator decision — the switch persists, not per-session).
    /// The occasion picker in `OccasionEditorSheet` persists the same way.
    @Bindable var savedArdaas: SavedArdaas

    /// Opens a just-made copy of this record in this Reader's place (#159).
    /// Nil hides Duplicate.
    private let onDuplicate: ((SavedArdaas) -> Void)?

    @Environment(\.modelContext) private var modelContext
    /// Every saved label, which the copy's label must not repeat.
    @Query private var allSavedArdaas: [SavedArdaas]

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
    /// This Reader's claim on keeping the screen awake (`ScreenAwake`).
    @State private var screenAwakeToken = UUID()
    @State private var isEditingArdaas = false
    /// "Transliteration off" etc., shown for a moment above the layer pills.
    @State private var layerHint: String?
    @State private var layerHintTask: Task<Void, Never>?

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// The whole window, safe areas included, in global coordinates. Its
    /// shape picks the layout, and its midline is the Duo's fold.
    @State private var windowFrame: CGRect = .zero
    /// Where the reading rows sit (inside the margins, left of any side
    /// bar), in global coordinates. The side-by-side columns split this.
    @State private var rowArea: CGRect = .zero
    /// The row at the top of the screen, so a change of layout (folding,
    /// unfolding, turning the open Duo) keeps the reader's place.
    @State private var topRow: Int?

    private static let fontScaleRange = 0.7...1.6
    private static let fontScaleStep = 0.1

    /// A comfortable line length for the single-column layouts on the open
    /// Duo. The phone layout is narrower than this anyway.
    private static let readableWidth: CGFloat = 620
    private static let sideBarWidth: CGFloat = 64

    private static let logger = Logger(subsystem: "io.sarbloc.ardaas", category: "Reader")

    init(savedArdaas: SavedArdaas, onDuplicate: ((SavedArdaas) -> Void)? = nil) {
        self.savedArdaas = savedArdaas
        self.onDuplicate = onDuplicate
    }

    var body: some View {
        content
            .navigationTitle(savedArdaas.label)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // On the open Duo these move into the side bar or the dock.
                if layout == .phone {
                    // Its own control, ahead of the gear: what the prayer is
                    // for is content, not a display preference, and burying
                    // it in a menu of layer toggles and text size would say
                    // otherwise.
                    if canEditOccasion {
                        ToolbarItem(placement: .topBarTrailing) {
                            occasionButton
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        displayMenu(includesShortcuts: true) {
                            Label("Display Options", systemImage: "gearshape")
                        }
                    }
                }
            }
            .background {
                Color.clear
                    .ignoresSafeArea()
                    .onGeometryChange(for: CGRect.self) { proxy in
                        proxy.frame(in: .global)
                    } action: { frame in
                        windowFrame = frame
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
            // Compose, reopened on this record (#154): label, variant,
            // occasion and benti, saved back in place.
            .sheet(isPresented: $isEditingArdaas) {
                ComposeView(editing: savedArdaas)
            }
            .onAppear {
                ScreenAwake.hold(screenAwakeToken)
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
                ScreenAwake.release(screenAwakeToken)
            }
    }

    /// The resolved variant's content once loading has succeeded.
    private var loadedContent: ArdaasContent? {
        guard case .success(let library) = loadResult else { return nil }
        return library.resolvedVariant(id: savedArdaas.variantId).content
    }

    private var layout: ReaderLayout {
        .choose(isRegularWidth: horizontalSizeClass == .regular, windowSize: windowFrame.size)
    }

    /// How each row lays out its lines under the current layout.
    private var arrangement: RowArrangement {
        switch layout {
        case .phone:
            return .full
        case .stacked:
            return .readable
        case .sideBySide:
            guard showsTwoColumns else { return .readable }
            return .columns(ReaderColumns.split(
                areaMinX: rowArea.minX,
                areaMaxX: rowArea.maxX,
                foldX: windowFrame.midX
            ))
        }
    }

    /// Two columns only when both have something in them: English on
    /// screen, and Gurmukhi or transliteration beside it. Otherwise one
    /// centred column, rather than a page with an empty half.
    private var showsTwoColumns: Bool {
        guard toggles.english, screenAvailability.english else { return false }
        let rendered = canonicalAvailability.renderedLayers(under: toggles)
        return rendered.contains(.gurmukhi) || rendered.contains(.transliteration)
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
        let arrangement = self.arrangement
        // One ScrollView in every layout, so the scroll position (and the
        // rows' identity) survives a change of layout; only the rows inside
        // it rearrange.
        return HStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: layout == .phone ? 20 : 24) {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                            switch item {
                            case .canonical(let segment):
                                segmentView(
                                    segment,
                                    isOccasionSlot: segment.id == occasionSlotId,
                                    arrangement: arrangement
                                )
                            case .benti(let layers):
                                bentiView(layers, arrangement: arrangement)
                            }
                        }
                    }
                    .scrollTargetLayout()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .onGeometryChange(for: CGRect.self) { geometry in
                        geometry.frame(in: .global)
                    } action: { frame in
                        rowArea = frame
                    }
                    .padding(.horizontal, horizontalMargin)
                    .padding(.vertical, 16)
                }
                .scrollPosition(id: $topRow, anchor: .top)
                // Rows above the fold change height when the layout does;
                // put the row that was at the top back there.
                .onChange(of: layout) {
                    if let topRow {
                        proxy.scrollTo(topRow, anchor: .top)
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                switch layout {
                case .phone: layerToggles
                case .stacked: dock
                case .sideBySide: EmptyView()
                }
            }
            .overlay(alignment: .bottom) {
                if layout == .sideBySide {
                    layerHintView
                        .padding(.bottom, 16)
                        .animation(.easeOut(duration: 0.2), value: layerHint)
                }
            }

            if layout == .sideBySide {
                sideBar
            }
        }
        .themedScreen()
    }

    private var horizontalMargin: CGFloat {
        switch layout {
        case .phone: 20
        case .stacked: 32
        case .sideBySide: 24
        }
    }

    // MARK: - Rows

    @ViewBuilder
    private func segmentView(
        _ segment: ArdaasSegment,
        isOccasionSlot: Bool,
        arrangement: RowArrangement
    ) -> some View {
        arranged(segmentLines(segment), arrangement: arrangement) {
            if isOccasionSlot, occasionWillNotAppear {
                strandedOccasionNote
            }
        }
        .frame(maxWidth: .infinity, alignment: arrangement.rowAlignment)
    }

    /// The segment's lines under the toggles, in layer order.
    private func segmentLines(_ segment: ArdaasSegment) -> [BentiLine] {
        // `renderedLayers` carries the never-blank guard: if the persisted
        // toggles leave this variant with nothing to show (e.g. Gurmukhi off
        // + only layers it lacks on), Gurmukhi renders anyway. It reasons
        // about the variant alone, never the benti — a benti-only English
        // layer keeps the screen non-empty but does nothing for a segment
        // that has no English.
        let rendered = canonicalAvailability.renderedLayers(under: toggles)
        var lines: [BentiLine] = []
        if rendered.contains(.gurmukhi) {
            lines.append(BentiLine(kind: .gurmukhi, text: segment.gurmukhi))
        }
        if rendered.contains(.transliteration), let transliteration = segment.transliteration {
            lines.append(BentiLine(kind: .transliteration, text: transliteration))
        }
        if rendered.contains(.english), let english = segment.english {
            lines.append(BentiLine(kind: .english, text: english))
        }
        return lines
    }

    /// The row's lines in the arrangement's shape. Columns put the reading
    /// layers on the left and English on the right, each at its fixed width,
    /// so every row's English starts level with its own Gurmukhi. `footer`
    /// goes under the left-hand (or only) column.
    @ViewBuilder
    private func arranged<Footer: View>(
        _ lines: [BentiLine],
        arrangement: RowArrangement,
        @ViewBuilder footer: () -> Footer
    ) -> some View {
        switch arrangement {
        case .columns(let columns):
            HStack(alignment: .top, spacing: ReaderColumns.gap) {
                lineStack(lines.filter { $0.kind != .english }, footer: footer)
                    .frame(width: columns.left, alignment: .leading)
                lineStack(lines.filter { $0.kind == .english }) { EmptyView() }
                    .frame(width: columns.right, alignment: .leading)
            }
        case .full:
            lineStack(lines, footer: footer)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .readable:
            lineStack(lines, footer: footer)
                .frame(maxWidth: Self.readableWidth, alignment: .leading)
        }
    }

    private func lineStack<Footer: View>(
        _ lines: [BentiLine],
        @ViewBuilder footer: () -> Footer
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(lines) { line in
                lineText(line)
            }
            footer()
        }
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
        // The occasion as the slot reads it — a legacy paath stored as the
        // occasion has moved out of it (`SlotChoice.normalised(in:)`).
        return savedArdaas.slotChoice.normalised(in: occasionCatalog).occasion
            .willNotAppear(in: loadedContent, catalog: occasionCatalog)
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
        .accessibilityHint("Opens the paath and occasion pickers")
    }

    /// The benti, rendered layer by layer exactly as a canonical segment is
    /// — same type sizes, colours and spacing — inside the kesri-tinted card
    /// that marks it out as the user's own words. Which layers appear is the
    /// pure decision in `BentiLayers.visibleLines`, including its never-blank
    /// fallback, so the card is never drawn empty.
    @ViewBuilder
    private func bentiView(_ layers: BentiLayers, arrangement: RowArrangement) -> some View {
        let lines = layers.visibleLines(under: toggles)
        switch arrangement {
        case .full:
            // The phone: the card inset within the margins, as it always was.
            arranged(lines, arrangement: arrangement) { EmptyView() }
                .padding(12)
                .background { BentiCard() }
        case .readable, .columns:
            // The card grows outwards into the margin instead, so the
            // benti's lines stay level with the columns of every other row
            // and the card spans both columns.
            arranged(lines, arrangement: arrangement) { EmptyView() }
                .padding(.vertical, 12)
                .background { BentiCard().padding(.horizontal, -12) }
                .frame(maxWidth: .infinity, alignment: arrangement.rowAlignment)
        }
    }

    /// One line of a row, styled by its layer. Canonical segments and the
    /// benti share it, so the two read as one continuous text.
    @ViewBuilder
    private func lineText(_ line: BentiLine) -> some View {
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

    // MARK: - Layer toggles

    /// Independent on/off pills for the two secondary layers (#169): two
    /// separate floating capsules, solid kesri when on, so each reads as its
    /// own switch rather than one two-way control. No bar behind them — the
    /// text scrolls underneath, and the inset keeps the last line clear.
    /// Gurmukhi's toggle stays in the display menu: it's the anchor layer,
    /// switched rarely. The phone layout's bottom chrome.
    @ViewBuilder
    private var layerToggles: some View {
        if hasLayerPills {
            VStack(spacing: 8) {
                layerHintView
                HStack(spacing: 10) {
                    layerPills(compact: false)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 20)
            .padding(.bottom, 6)
            .background(bottomFade)
            .animation(.easeOut(duration: 0.2), value: layerHint)
        }
    }

    /// Pills only for layers something on screen actually carries — the
    /// variant or the benti. Using the screen-wide availability (not the
    /// variant's) is what keeps the user's own words controllable on a
    /// variant with no attested English: the benti's English renders there,
    /// so its pill has to be there to switch it off. No pills at all when
    /// neither the variant nor the benti has either layer.
    private var hasLayerPills: Bool {
        screenAvailability.transliteration || screenAvailability.english
    }

    /// The pills themselves, for the caller's stack: a row on the phone and
    /// in the dock, a column in the side bar (`compact`, to fit its width).
    @ViewBuilder
    private func layerPills(compact: Bool) -> some View {
        if screenAvailability.transliteration {
            layerToggle(.transliteration, isOn: $showTransliteration, compact: compact)
        }
        if screenAvailability.english {
            layerToggle(.english, isOn: $showEnglish, compact: compact)
        }
    }

    @ViewBuilder
    private var layerHintView: some View {
        if let layerHint {
            Text(layerHint)
                .font(.footnote.weight(.medium))
                .foregroundStyle(Theme.parchment)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(.ultraThinMaterial, in: Capsule())
                .transition(.opacity)
                // VoiceOver already announces the toggle's new state.
                .accessibilityHidden(true)
        }
    }

    /// A soft fade, not a bar: the text dims as it passes under the
    /// controls instead of competing with them.
    private var bottomFade: some View {
        LinearGradient(
            colors: [.clear, Theme.navyBottom.opacity(0.92)],
            startPoint: .top,
            endPoint: .bottom
        )
        .ignoresSafeArea(edges: .bottom)
    }

    private func layerToggle(_ kind: LayerKind, isOn: Binding<Bool>, compact: Bool) -> some View {
        let isPinned = isPinnedLayer(kind)
        let binding = Binding(
            get: { isOn.wrappedValue },
            set: { newValue in
                isOn.wrappedValue = newValue
                flashLayerHint("\(kind.pillTitle) \(newValue ? "on" : "off")")
            }
        )
        return Toggle(isOn: binding) {
            Text(kind.pillGlyph)
        }
        .toggleStyle(LayerPillToggleStyle(compact: compact))
        .disabled(isPinned)
        .opacity(isPinned ? 0.55 : 1)
        // The glyph is shorthand; VoiceOver gets the full name as a switch.
        .accessibilityRepresentation {
            Toggle(kind.pillTitle, isOn: binding)
                .disabled(isPinned)
        }
    }

    /// Names the layer just switched, briefly — the glyphs are compact, and
    /// this is how a first-time reader learns which is which.
    private func flashLayerHint(_ text: String) {
        layerHintTask?.cancel()
        layerHint = text
        layerHintTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.5))
            guard !Task.isCancelled else { return }
            layerHint = nil
        }
    }

    // MARK: - Open Duo controls (#171)

    /// The stacked layout's bottom chrome (the Duo open upright): the tools
    /// in a capsule on the left, the layer pills on the right, across the
    /// width of the reading column.
    private var dock: some View {
        VStack(spacing: 8) {
            layerHintView
            HStack(spacing: 12) {
                HStack(spacing: 2) {
                    textSizeMenu
                    if canEditOccasion {
                        toolButton(OccasionCopy.title, systemImage: "tag") {
                            isEditingOccasion = true
                        }
                    }
                    toolButton("Edit", systemImage: "pencil") {
                        isEditingArdaas = true
                    }
                    displayMenu(includesShortcuts: false) {
                        toolLabel("Display Options", systemImage: "gearshape")
                    }
                }
                .padding(.horizontal, 6)
                .background(.ultraThinMaterial, in: Capsule())

                Spacer(minLength: 0)

                HStack(spacing: 10) {
                    layerPills(compact: false)
                }
            }
            .frame(maxWidth: Self.readableWidth)
            .padding(.horizontal, horizontalMargin)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 20)
        .padding(.bottom, 6)
        .background(bottomFade)
        .animation(.easeOut(duration: 0.2), value: layerHint)
    }

    /// The side-by-side layout's controls (the Duo open sideways): a bar
    /// down the right-hand edge with everything at the bottom — clear of the
    /// status bar, and under the thumb. Settings, edit, occasion and text
    /// size, then the layer pills.
    private var sideBar: some View {
        VStack(spacing: 4) {
            Spacer(minLength: 0)
            displayMenu(includesShortcuts: false) {
                toolLabel("Display Options", systemImage: "gearshape")
            }
            toolButton("Edit", systemImage: "pencil") {
                isEditingArdaas = true
            }
            if canEditOccasion {
                toolButton(OccasionCopy.title, systemImage: "tag") {
                    isEditingOccasion = true
                }
            }
            textSizeMenu
            if hasLayerPills {
                Rectangle()
                    .fill(Theme.mist.opacity(0.3))
                    .frame(width: 28, height: 1)
                    .padding(.vertical, 8)
                VStack(spacing: 2) {
                    layerPills(compact: true)
                }
            }
        }
        .padding(.bottom, 12)
        .frame(width: Self.sideBarWidth)
        .frame(maxHeight: .infinity)
        .background {
            Theme.navyBottom.opacity(0.55)
                .overlay(alignment: .leading) {
                    Rectangle()
                        .fill(Color.white.opacity(0.08))
                        .frame(width: 1)
                }
                .ignoresSafeArea()
        }
    }

    /// An icon-only control for the dock and the side bar, with a 44pt
    /// touch target. The title is what VoiceOver reads.
    private func toolLabel(_ title: String, systemImage: String) -> some View {
        Label(title, systemImage: systemImage)
            .labelStyle(.iconOnly)
            .font(.title3)
            .foregroundStyle(Theme.parchment)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
    }

    private func toolButton(
        _ title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            toolLabel(title, systemImage: systemImage)
        }
        .buttonStyle(.plain)
    }

    /// Text size as its own control on the open Duo, where there's room;
    /// on the phone it lives in the display menu.
    private var textSizeMenu: some View {
        Menu {
            textSizeButtons
        } label: {
            toolLabel("Text Size", systemImage: "textformat.size")
        }
    }

    // MARK: - Toolbar

    /// Only when there is something to pick *and* somewhere to put it: a
    /// catalog that loaded, and a variant that declares an occasion slot. A
    /// picker that could never change a word would be a lie.
    private var canEditOccasion: Bool {
        occasionCatalog != nil && loadedContent?.occasionSlot != nil
    }

    /// Reopens this Ardaas in Compose to change any of what it was saved
    /// with (#154). Lives at the top of `displayMenu` rather than in the
    /// toolbar, which already holds Occasion and the gear.
    private var editButton: some View {
        Button {
            isEditingArdaas = true
        } label: {
            Label("Edit", systemImage: "pencil")
        }
    }

    /// Saves a copy of this Ardaas — every field, a new label — and opens it
    /// in this Reader's place (#159). Sits under Edit: both act on the record
    /// rather than on how it is displayed.
    private var duplicateButton: some View {
        Button {
            duplicate()
        } label: {
            Label("Duplicate", systemImage: "plus.square.on.square")
        }
    }

    private func duplicate() {
        guard let onDuplicate else { return }
        let copy = savedArdaas.duplicate(existingLabels: allSavedArdaas.map(\.label))
        modelContext.insert(copy)
        // Saved before it is navigated to, so the path holds a record with
        // its permanent identity. A failed save leaves the copy inserted for
        // autosave to retry; it is still the record to open.
        do {
            try modelContext.save()
        } catch {
            Self.logger.error("Saving a duplicated Ardaas failed: \(String(describing: error), privacy: .public)")
        }
        onDuplicate(copy)
    }

    /// Opens the paath and occasion pickers (#158). Kept out of `displayMenu` — that menu is
    /// display preferences (which layers, how big); this changes the words of
    /// the Ardaas itself, and it is the one thing on this screen a reader may
    /// want to change between two recitations.
    private var occasionButton: some View {
        Button {
            isEditingOccasion = true
        } label: {
            Label(OccasionCopy.title, systemImage: "tag")
        }
    }

    /// The gear menu. `includesShortcuts` on the phone, where Edit and text
    /// size have nowhere else to go; on the open Duo they have their own
    /// buttons, so the menu drops them rather than offer them twice.
    private func displayMenu<MenuLabel: View>(
        includesShortcuts: Bool,
        @ViewBuilder label: () -> MenuLabel
    ) -> some View {
        Menu {
            Section {
                if includesShortcuts {
                    editButton
                }
                if onDuplicate != nil {
                    duplicateButton
                }
            }
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
            if includesShortcuts {
                Section("Text Size") {
                    textSizeButtons
                }
            }
        } label: {
            label()
        }
    }

    @ViewBuilder
    private var textSizeButtons: some View {
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
        occasion: .catalog(id: "birthday"),
        paathId: "japji-sahib"
    )
    container.mainContext.insert(sample)
    return NavigationStack {
        ReaderView(savedArdaas: sample)
    }
    .modelContainer(container)
}

/// How a Reader row lays out its lines (#171).
private enum RowArrangement {
    /// The phone: one column, the full width between the margins.
    case full
    /// One column at a readable width, centred: the Duo open upright, or
    /// open sideways with nothing to put in a second column.
    case readable
    /// The Duo open sideways: reading layers left, English right.
    case columns(ReaderColumns)

    var rowAlignment: Alignment {
        switch self {
        case .readable: .center
        case .full, .columns: .leading
        }
    }
}

/// The kesri-tinted card behind the benti, with its leading stripe.
private struct BentiCard: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 10)
            .fill(Color.accentColor.opacity(0.12))
            .overlay(alignment: .leading) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.accentColor.opacity(0.6))
                    .frame(width: 3)
                    .padding(.vertical, 6)
            }
    }
}

/// The Reader's layer pill (#169): solid kesri with navy text when on, a
/// translucent capsule with dim text when off. Liquid Glass where the OS has
/// it, material before that.
private struct LayerPillToggleStyle: ToggleStyle {
    /// Narrower, for the open Duo's 64pt side bar.
    var compact = false

    func makeBody(configuration: Configuration) -> some View {
        Button {
            configuration.isOn.toggle()
        } label: {
            configuration.label
                .font(.subheadline.weight(.bold))
                .foregroundStyle(configuration.isOn ? Theme.navyBottom : Theme.mist)
                .frame(minWidth: compact ? 40 : 52, minHeight: 34)
                .padding(.horizontal, compact ? 6 : 12)
                .background { LayerPillBackground(isOn: configuration.isOn) }
                // A 44pt touch target around the 34pt pill.
                .padding(.vertical, 5)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct LayerPillBackground: View {
    let isOn: Bool

    var body: some View {
        if isOn {
            Capsule()
                .fill(Theme.kesri)
                .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
        } else if #available(iOS 26, *) {
            Color.clear
                .glassEffect(.regular, in: Capsule())
        } else {
            Capsule()
                .fill(.ultraThinMaterial)
                .overlay(Capsule().strokeBorder(Color.white.opacity(0.14), lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
        }
    }
}

private extension LayerKind {
    /// The pill's compact face.
    var pillGlyph: String {
        switch self {
        case .gurmukhi: "ੳ"
        case .transliteration: "ਅ→A"
        case .english: "EN"
        }
    }

    /// The full name, for VoiceOver and the on/off hint.
    var pillTitle: String {
        switch self {
        case .gurmukhi: "Gurmukhi"
        case .transliteration: "Transliteration"
        case .english: "Translation"
        }
    }
}

/// Keeps the screen awake while any Reader is showing — a reader
/// mid-recitation must not lose the text.
///
/// Held per Reader rather than switched on and off by each: two overlap when
/// a duplicate opens in its original's place (#159), and the outgoing
/// Reader's `onDisappear` can land after the incoming one's `onAppear`.
/// Tokens, not a count, so a repeated `onAppear` cannot leave the screen
/// awake for good.
@MainActor
enum ScreenAwake {
    private static var holders: Set<UUID> = []

    static func hold(_ token: UUID) {
        holders.insert(token)
        UIApplication.shared.isIdleTimerDisabled = true
    }

    static func release(_ token: UUID) {
        holders.remove(token)
        if holders.isEmpty {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
}
