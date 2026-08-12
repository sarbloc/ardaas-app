import SwiftData
import SwiftUI

/// Compose screen: enter a label and a personal benti, then save it as a
/// `SavedArdaas`. Presentation-agnostic — the presenter (e.g. the Home
/// screen) shows it as a sheet; it dismisses itself after saving or on
/// cancel.
///
/// ## Translation (#44)
///
/// The benti can be written in English and translated to Gurmukhi entirely on
/// device, or typed straight in Gurmukhi. Which one is happening is decided by
/// `BentiScriptDetector` from what is in the editor:
///
/// * **Gurmukhi typed** → no translation at all. The transliteration is
///   generated live by `GurmukhiTransliterator`, which is deterministic and
///   needs no model, so this path works in every build.
/// * **Anything else** → a Translate action, which the first time presents
///   `TranslationConsentView` and downloads nothing until the user confirms
///   there. The result is a *draft*: shown with a machine-translation
///   disclaimer, editable, and with its transliteration regenerated from
///   whatever the user leaves in the Gurmukhi editor.
///
/// In a build without the ONNX Runtime linked (`TranslationBuild
/// .isEngineAvailable == false`, which is every release build today) the
/// Translate action is not shown at all rather than offered and then failing —
/// but the Gurmukhi-typed path above is untouched.
///
/// Saving writes all three layers. Saving without translating writes English
/// only, exactly as before this screen could translate.
struct ComposeView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    /// Owned by this screen: the download it may start is cancelled when the
    /// screen goes away (partial files are kept and resume), which is why the
    /// consent copy asks the user to keep the screen open.
    @StateObject private var translation = BentiTranslationService()

    /// Which text input holds the keyboard, so it can be given up. Tracked
    /// rather than fired blind at `UIResponder` so the tap-to-dismiss gesture
    /// below can be attached only while a field is actually focused.
    private enum Field: Hashable {
        case label
        case benti
        case gurmukhi
    }

    @FocusState private var focusedField: Field?

    @State private var label = ""
    @State private var draft = BentiDraft()
    @State private var variantId = ArdaasLibrary.defaultVariantId

    /// Loaded once; failure hides the picker and saves fall back to the
    /// default variant (a broken bundle already surfaces in the Reader).
    @State private var library: ArdaasLibrary?

    @State private var isShowingConsent = false
    @State private var isConfirmingStaleSave = false
    @State private var isTranslating = false
    /// The user asked to translate but the model had to be installed first;
    /// translate for them once it is ready rather than making them tap again.
    @State private var translateWhenReady = false
    @State private var translationError: String?
    /// Held so dismissing the screen abandons the result rather than resuming
    /// into a view that is gone. It does not stop the inference itself — the
    /// decode loop checks no cancellation (see #52) — but the service is
    /// released as soon as it returns.
    @State private var translationTask: Task<Void, Never>?

    private var trimmedLabel: String {
        label.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A translation the user has explicitly asked for is still coming: either
    /// running, or waiting on the install they just consented to.
    ///
    /// Saving now would persist the layers as they were *before* the result
    /// lands — an English-only record on a first translate, or the previous
    /// Gurmukhi against edited English on a re-translate — and dismissing
    /// Compose cancels the install as well. So Save waits it out, and the
    /// progress row says so and points at Cancel download as the way out.
    private var isAwaitingTranslation: Bool {
        isTranslating || (translateWhenReady && translation.state.isWorking)
    }

    private var canSave: Bool {
        !trimmedLabel.isEmpty && !draft.isEmpty && !isAwaitingTranslation
    }

    /// Whether the translation section is on screen at all. Hidden without an
    /// engine (it could only fail) and for Gurmukhi input (nothing to
    /// translate) — but shown, disabled, for an empty editor, so the feature is
    /// discoverable before anything is typed.
    ///
    /// The `isWorking` term keeps an install visible when the user switches to
    /// Gurmukhi mid-download: the transfer carries on regardless, and hiding it
    /// would hide the progress *and* the only Cancel button. A failure is not
    /// held open the same way — `state` is sticky, so it surfaces again as soon
    /// as the text goes back to being translatable.
    private var showsTranslationSection: Bool {
        TranslationBuild.isEngineAvailable
            && (!draft.isTypedInGurmukhi || translation.state.isWorking)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Label") {
                    TextField("e.g. Family ardaas", text: $label)
                        .focused($focusedField, equals: .label)
                        .submitLabel(.done)
                        .onSubmit { focusedField = nil }
                }
                .listRowBackground(Theme.raisedFill)

                if let library {
                    Section("Ardaas") {
                        Picker("Ardaas", selection: $variantId) {
                            ForEach(library.variants) { variant in
                                Text(variant.displayName).tag(variant.id)
                            }
                        }
                        .pickerStyle(.menu)
                        .accessibilityLabel("Ardaas variant")
                    }
                    .listRowBackground(Theme.raisedFill)
                }

                Section("Benti") {
                    TextEditor(text: $draft.typed)
                        .frame(minHeight: 160)
                        .focused($focusedField, equals: .benti)
                        .accessibilityLabel("Benti")
                }
                .listRowBackground(Theme.raisedFill)

                if draft.isTypedInGurmukhi {
                    transliterationSection
                }
                if showsTranslationSection {
                    translateSection
                    // False whenever the typed text is Gurmukhi, so a draft
                    // from earlier English stays hidden while it is ignored.
                    if draft.hasTranslationDraft {
                        gurmukhiDraftSection
                    }
                }

                Section {
                    gurmukhiKeyboardTip
                }
                .listRowBackground(Theme.raisedFill)
            }
            .themedScreen()
            // Three ways out of the keyboard, because a Form gives none by
            // default and a benti is long enough that a stuck keyboard hides
            // half the screen.
            //
            // 1. Drag the list down — standard iOS, costs nothing.
            .scrollDismissesKeyboard(.interactively)
            // 2. Tap anywhere else. `simultaneousGesture` so it does not
            //    consume the tap (buttons and rows still work), and attached
            //    ONLY while a field is focused: with no gesture installed on
            //    first tap, focusing a field can never race its own dismissal.
            .simultaneousGesture(
                focusedField == nil
                    ? nil
                    : TapGesture().onEnded { focusedField = nil }
            )
            // 3. An explicit Done above the keyboard — the escape hatch that
            //    always works, including for VoiceOver and hardware keyboards.
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focusedField = nil }
                }
            }
            .onAppear {
                if library == nil {
                    library = try? ArdaasLibrary.loadBundled()
                }
                translation.refresh()
            }
            .onDisappear {
                translationTask?.cancel()
                translationTask = nil
            }
            // The install the user consented to has finished: run the
            // translation they already asked for rather than making them tap
            // again. The request is consumed here either way — if the text has
            // since become untranslatable (emptied, or rewritten in Gurmukhi)
            // `translate()` no-ops and the button is right there, enabled the
            // moment the text is translatable again. Holding the request open
            // instead would mean firing inference on a half-typed sentence
            // later on, which is worse than one extra tap.
            .onChange(of: translation.state) { _, newState in
                guard newState.isReady, translateWhenReady else { return }
                translateWhenReady = false
                translate()
            }
            .sheet(isPresented: $isShowingConsent) {
                TranslationConsentView(
                    downloadBytes: translation.downloadBytes,
                    peakDiskBytes: translation.peakDiskBytes,
                    installedDiskBytes: translation.installedDiskBytes
                ) { allowCellular in
                    translationError = nil
                    translateWhenReady = true
                    translation.download(allowingCellular: allowCellular)
                }
            }
            .confirmationDialog(
                "The Gurmukhi no longer matches your English",
                isPresented: $isConfirmingStaleSave,
                titleVisibility: .visible
            ) {
                Button("Save both anyway") { save(draft.layers) }
                Button("Save the English only", role: .destructive) {
                    // Computed before the state write so the record is built
                    // from a value we hold, not from a just-mutated @State.
                    var cleared = draft
                    cleared.clearTranslation()
                    draft = cleared
                    save(cleared.layers)
                }
                Button("Keep editing", role: .cancel) {}
            } message: {
                Text("You edited the English after translating it, so the two layers say different things. Translate again to update the Gurmukhi — or save them as they are.")
            }
            .navigationTitle("New Ardaas")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        // A stale draft would persist Gurmukhi that says
                        // something different from the English next to it, and
                        // nothing outside this screen can tell that later — so
                        // it is never saved without an explicit answer.
                        if draft.isStale {
                            isConfirmingStaleSave = true
                        } else {
                            save(draft.layers)
                        }
                    }
                    .disabled(!canSave)
                }
            }
        }
    }

    // MARK: - Gurmukhi typed directly

    /// No model involved: the transliteration is generated from the typed
    /// Gurmukhi by pure rules, so this renders in every build, offline, with
    /// nothing installed.
    private var transliterationSection: some View {
        Section("Transliteration") {
            Text(draft.transliteration)
                .font(.callout)
                .italic()
                .foregroundStyle(Theme.sand)
                .textSelection(.enabled)
                .accessibilityLabel("Transliteration")
            Text("Generated from your Gurmukhi. Saved alongside it so you can read the Ardaas in either script.")
                .font(.footnote)
                .foregroundStyle(Theme.mist)
        }
        .listRowBackground(Theme.raisedFill)
    }

    // MARK: - Translate

    @ViewBuilder
    private var translateSection: some View {
        Section("Gurmukhi") {
            switch translation.state {
            case .notDownloaded:
                translateButton
                Text("Translation runs on this phone. The first time, it downloads a \(Self.bytes(translation.downloadBytes)) model — you'll see exactly what before anything starts.")
                    .font(.footnote)
                    .foregroundStyle(Theme.mist)

            case .downloading(let progress):
                VStack(alignment: .leading, spacing: 6) {
                    Text("Downloading the translation model — \(Int(progress * 100))%")
                        .font(.footnote)
                        .foregroundStyle(Theme.mist)
                    ProgressView(value: progress)
                }
                .accessibilityElement(children: .combine)
                Text(translateWhenReady
                     ? "Keep this screen open — leaving cancels the download, and Save waits until your translation is done. Cancel below to save your English benti on its own."
                     : "Keep this screen open. If you leave, whatever finished is kept and it picks up from there next time.")
                    .font(.footnote)
                    .foregroundStyle(Theme.mist)
                Button("Cancel download", role: .cancel) {
                    translateWhenReady = false
                    translation.cancelDownload()
                }

            case .optimizing:
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Preparing the model — one time only.")
                        .font(.footnote)
                        .foregroundStyle(Theme.mist)
                }
                .accessibilityElement(children: .combine)
                // Optimization cannot be interrupted, so this doesn't stop it —
                // it drops the queued translate, which is what Save is waiting
                // on. Without it the screen has no way out of the wait, since
                // Cancel download only exists while downloading.
                if translateWhenReady {
                    Button("Don't wait — save without translating") {
                        translateWhenReady = false
                    }
                    .font(.footnote)
                }

            case .ready:
                translateButton
                Text("Runs on this phone. Your benti is not uploaded anywhere.")
                    .font(.footnote)
                    .foregroundStyle(Theme.mist)

            case .failed(let error):
                Label(Self.message(for: error), systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.red)
                Button("Try again") { isShowingConsent = true }
            }

            if let translationError {
                Label(translationError, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var translateButton: some View {
        Button {
            if translation.state.isReady {
                translate()
            } else {
                // Nothing is downloaded until the user confirms in there.
                isShowingConsent = true
            }
        } label: {
            if isTranslating {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("Translating…")
                }
            } else {
                Label(
                    draft.hasTranslationDraft ? "Translate again" : "Translate to Gurmukhi",
                    systemImage: "character.book.closed")
            }
        }
        .disabled(!draft.isTranslatable || isTranslating)
    }

    /// The reviewable draft: editable Gurmukhi, its regenerated
    /// transliteration, and an honest disclaimer about where it came from.
    private var gurmukhiDraftSection: some View {
        Section("Gurmukhi draft") {
            TextEditor(text: $draft.gurmukhi)
                .frame(minHeight: 110)
                .font(.title3)
                .foregroundStyle(Theme.parchment)
                .focused($focusedField, equals: .gurmukhi)
                .accessibilityLabel("Gurmukhi benti")

            if !draft.transliteration.isEmpty {
                Text(draft.transliteration)
                    .font(.footnote)
                    .italic()
                    .foregroundStyle(Theme.sand)
                    .textSelection(.enabled)
                    .accessibilityLabel("Transliteration")
            }

            VStack(alignment: .leading, spacing: 4) {
                Label("Machine translation — check before saving", systemImage: "exclamationmark.triangle")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Theme.kesri)
                Text("This was translated on your phone by a small model, not by a person. Read it, edit the Gurmukhi if anything is off — the transliteration updates as you do — then save.")
                    .font(.footnote)
                    .foregroundStyle(Theme.mist)
            }
            .padding(.vertical, 2)

            if draft.isStale {
                Label("You've changed the English since this was translated. Translate again to update the Gurmukhi.", systemImage: "arrow.triangle.2.circlepath")
                    .font(.footnote)
                    .foregroundStyle(Theme.kesri)
            }

            Button("Remove Gurmukhi", role: .destructive) {
                draft.clearTranslation()
                translationError = nil
            }
            .font(.footnote)
        }
        .listRowBackground(Theme.raisedFill)
    }

    private var gurmukhiKeyboardTip: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "keyboard")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text("Type in Gurmukhi")
                    .font(.subheadline.weight(.semibold))
                Text("You can write your benti in Gurmukhi by adding the Punjabi keyboard: Settings → General → Keyboard → Add New Keyboard → Punjabi (Gurmukhi).")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }

    // MARK: - Actions

    @MainActor
    private func translate() {
        guard !isTranslating, draft.isTranslatable, translation.state.isReady else { return }
        let source = draft.translationSource
        guard !source.isEmpty else { return }
        isTranslating = true
        translationError = nil
        translationTask?.cancel()
        translationTask = Task { @MainActor in
            do {
                let gurmukhi = try await translation.translate(source)
                // The screen may have gone (dismissed) while this ran; don't
                // write a result nobody is looking at.
                if Task.isCancelled { return }
                if gurmukhi.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    translationError = "The translation came back empty. Try rewording your benti."
                } else {
                    draft.applyTranslation(gurmukhi, of: source)
                }
            } catch {
                if Task.isCancelled { return }
                translationError = Self.message(for: TranslationError.wrap(error))
            }
            isTranslating = false
        }
    }

    private func save(_ layers: BentiLayers) {
        guard canSave, !layers.isEmpty else { return }
        modelContext.insert(
            SavedArdaas(
                label: trimmedLabel,
                bentiText: layers.english,
                variantId: variantId,
                bentiGurmukhi: layers.gurmukhi,
                bentiTransliteration: layers.transliteration
            )
        )
        dismiss()
    }

    // MARK: - Copy

    /// User-facing wording for the failures reachable from this screen. Falls
    /// back to the error's own text rather than hiding it — a silent failure
    /// would be worse than a technical one.
    private static func message(for error: TranslationError) -> String {
        switch error {
        case .cellularNotAllowed:
            return "The download needs Wi-Fi, or your permission to use mobile data."
        case .insufficientDisk:
            return "Not enough free space on this phone to install the translation model."
        case .insufficientMemory:
            return "Not enough free memory to translate right now. Close a few apps and try again."
        case .inputTooLong:
            return "This benti is too long to translate in one go. Try a shorter one."
        case .cancelled:
            return "Cancelled."
        case .engineUnavailable, .notReady:
            return "Translation isn't ready yet."
        default:
            return error.description
        }
    }

    /// Decimal units, matching how iOS reports storage to the user.
    private static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }
}

#Preview {
    let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
    let container = try! ModelContainer(for: SavedArdaas.self, configurations: configuration)
    return ComposeView()
        .modelContainer(container)
}
