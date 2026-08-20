import SwiftData
import SwiftUI

/// Changing the "….." slot from the Reader (#67), on a record that already
/// exists.
///
/// Presented as a sheet rather than folded into the Reader's display menu:
/// what the prayer is for is *content*, not a display preference, and the
/// shared `OccasionSection` needs room for the explanation, a pushed list of
/// seventeen two-line rows, a free-text field and the sentence quoted back —
/// none of which belong in a gear menu.
///
/// ## Writes straight to the record
///
/// Every change persists immediately, exactly as the Reader's variant picker
/// does (operator decision): the reader behind the sheet re-renders as the
/// choice is made, so what they see is what is saved. There is no Cancel for
/// the same reason — Done dismisses, it does not commit.
///
/// The consequence worth naming: selecting "Other…" stores "nothing chosen"
/// until words are typed, so the dots come back for those few keystrokes.
/// That is `OccasionDraft.choice`'s rule (an abandoned draft is not a
/// choice), applied live rather than at Save.
struct OccasionEditorSheet: View {
    @Bindable var savedArdaas: SavedArdaas
    let catalog: OccasionCatalog
    /// The variant the Reader is showing, so the preview and the #72 warning
    /// describe the text actually on screen behind the sheet.
    let content: ArdaasContent?

    @Environment(\.dismiss) private var dismiss

    /// Seeded from the record, so the picker opens on the current choice.
    /// Held separately because the picker has one state the record does not
    /// — "Other…" with nothing typed yet (see `OccasionDraft`).
    @State private var draft: OccasionDraft

    private enum Field: Hashable {
        case occasion
    }

    @FocusState private var focusedField: Field?

    init(savedArdaas: SavedArdaas, catalog: OccasionCatalog, content: ArdaasContent?) {
        self.savedArdaas = savedArdaas
        self.catalog = catalog
        self.content = content
        _draft = State(initialValue: OccasionDraft(choice: savedArdaas.occasionChoice))
    }

    var body: some View {
        NavigationStack {
            Form {
                OccasionSection(
                    draft: $draft,
                    catalog: catalog,
                    content: content,
                    focus: $focusedField,
                    focusValue: Field.occasion
                )
            }
            .themedScreen()
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Occasion")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focusedField = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        focusedField = nil
                        dismiss()
                    }
                }
            }
            // The write. Driven by the draft rather than by each control so
            // every route to a change — a row, the free-text field, clearing
            // it — persists through the one rule (`OccasionDraft.choice`).
            .onChange(of: draft) { _, updated in
                savedArdaas.occasionChoice = updated.choice
            }
        }
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
        occasion: .catalog(id: "japji-sahib")
    )
    container.mainContext.insert(sample)
    return OccasionEditorSheet(
        savedArdaas: sample,
        catalog: try! OccasionCatalog.loadBundled(),
        content: try! ArdaasLibrary.loadBundled().defaultVariant.content
    )
    .modelContainer(container)
}
