import SwiftUI

/// The user-facing copy for the "….." slot, in one place so Compose (#66),
/// the Reader's picker and the Reader's own slot warning (#67) say the same
/// thing in the same words.
enum OccasionCopy {
    /// What the gap is and that leaving it is normal — the teaching text.
    static let explanation = "Partway through the Ardaas there's a gap, written as “…..”. It's where you name what the prayer is for — the paath (scripture reading) you've just finished, or an occasion like a birthday, a new home, or someone's recovery. Leaving it unfilled is completely normal; the dots stay as they are."

    /// How free text is placed: one script, no translation.
    static let freeText = "Write it in Gurmukhi and it goes into the Gurmukhi text; write it in English and it goes into the English. It isn't translated for you."

    /// The #72 gap said plainly: this choice lands in no layer this Ardaas
    /// carries. Reachable when choosing, and again when the Reader's variant
    /// picker moves free text onto an Ardaas that cannot show it.
    static let willNotAppear = "This Ardaas has no English line, so an occasion written in English has nowhere to go — the “…..” stays as it is. Write it in Gurmukhi instead, or pick one from the list."
}

/// The "….." slot as one shared control: what the gap is, the bundled list
/// (#64) plus free text, the slot sentence quoted back with the choice
/// spliced in, and the #72 warning.
///
/// Extracted from Compose (#66) when the Reader gained the same picker (#67)
/// rather than copied: the two screens must offer the same rows, the same
/// wording and the same warning, and the preview has to keep coming from
/// `ArdaasComposer` — one substitution, so neither screen can promise
/// something the Reader won't render.
///
/// A `Section`, so each caller places it in its own `Form` and owns the
/// surrounding chrome. Focus is passed in rather than owned here so the
/// host's keyboard dismissal (its Done button, its tap-to-dismiss gesture)
/// keeps covering the free-text field: generic over the host's focus enum,
/// which is the one thing the two screens genuinely differ on.
struct OccasionSection<Focus: Hashable>: View {
    /// The picker's state. The host persists whatever it decides from
    /// `draft.choice` — on Save in Compose, immediately in the Reader.
    @Binding var draft: OccasionDraft
    let catalog: OccasionCatalog
    /// The selected variant's text, which the preview and the warning both
    /// depend on. Nil while the bundle is loading: both are simply omitted
    /// until it arrives.
    let content: ArdaasContent?
    var focus: FocusState<Focus?>.Binding
    /// The host's focus case for the free-text field.
    let focusValue: Focus

    var body: some View {
        Section("Occasion") {
            Text(OccasionCopy.explanation)
                .font(.footnote)
                .foregroundStyle(Theme.mist)

            // Titled as a sentence rather than "Occasion" again: the row
            // reads "What it's for — None" under the section header.
            Picker("What it's for", selection: $draft.selection) {
                Text("None").tag(OccasionSelection.unset)

                Section("After a paath") {
                    ForEach(catalog.occasions(in: .paath)) { entry in
                        row(entry).tag(OccasionSelection.catalog(id: entry.id))
                    }
                }

                Section("Occasions") {
                    ForEach(catalog.occasions(in: .occasion)) { entry in
                        row(entry).tag(OccasionSelection.catalog(id: entry.id))
                    }
                }

                Text("Other…").tag(OccasionSelection.custom)
            }
            // Pushed rather than a menu: seventeen rows, each two lines
            // (Gurmukhi over English), is a list, not a popover.
            .pickerStyle(.navigationLink)

            if draft.isCustom {
                TextField("e.g. my daughter's first birthday", text: $draft.customText)
                    .focused(focus, equals: focusValue)
                    .submitLabel(.done)
                    .onSubmit { focus.wrappedValue = nil }
                    .accessibilityLabel("Your own occasion")

                Text(OccasionCopy.freeText)
                    .font(.footnote)
                    .foregroundStyle(Theme.mist)
            }

            if let content {
                if let preview = draft.preview(in: content, catalog: catalog) {
                    previewRow(preview)
                }
                // The one combination the text cannot render — see #72. Said
                // plainly here rather than accepted silently, because the
                // choice is kept either way and would otherwise just seem to
                // do nothing.
                if draft.willNotAppear(in: content, catalog: catalog) {
                    Label(OccasionCopy.willNotAppear, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(Theme.kesri)
                }
            }
        }
        .listRowBackground(Theme.raisedFill)
    }

    /// One catalog row: the Gurmukhi as it will appear in the Ardaas, with
    /// its English underneath for anyone who doesn't read Gurmukhi.
    private func row(_ entry: Occasion) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(entry.gurmukhi)
                .foregroundStyle(Theme.parchment)
            Text(entry.english)
                .font(.footnote)
                .foregroundStyle(Theme.mist)
        }
    }

    /// The slot sentence quoted back, in the layer the choice landed in —
    /// the same substitution the Reader performs (`ArdaasComposer`), so this
    /// is a promise the Reader keeps.
    private func previewRow(_ preview: OccasionPreview) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(preview.isFilled ? "How it will read" : "Where it goes")
                .font(.caption)
                .foregroundStyle(Theme.mist)
            Text(preview.text)
                .font(.callout)
                .italic(preview.layer == .transliteration)
                .foregroundStyle(Self.color(for: preview.layer))
                .textSelection(.enabled)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    /// The Reader's layer colours, so a line looks the same in the preview as
    /// it will in the Ardaas itself.
    private static func color(for layer: LayerKind) -> Color {
        switch layer {
        case .gurmukhi: return Theme.parchment
        case .transliteration: return Theme.sand
        case .english: return Theme.mist
        }
    }
}
