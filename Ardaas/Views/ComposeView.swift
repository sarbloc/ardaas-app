import SwiftData
import SwiftUI

/// Compose screen: enter a label and a personal benti, then save it as a
/// `SavedArdaas`. Presentation-agnostic — the presenter (e.g. the Home
/// screen) shows it as a sheet; it dismisses itself after saving or on
/// cancel.
struct ComposeView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @AppStorage("hasSeenGurmukhiKeyboardTip") private var hasSeenGurmukhiKeyboardTip = false

    @State private var label = ""
    @State private var bentiText = ""

    private var trimmedLabel: String {
        label.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var trimmedBenti: String {
        bentiText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSave: Bool {
        !trimmedLabel.isEmpty && !trimmedBenti.isEmpty
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Label") {
                    TextField("e.g. Family ardaas", text: $label)
                }
                .listRowBackground(Theme.raisedFill)

                Section("Benti") {
                    TextEditor(text: $bentiText)
                        .frame(minHeight: 160)
                        .accessibilityLabel("Benti")
                }
                .listRowBackground(Theme.raisedFill)

                if !hasSeenGurmukhiKeyboardTip {
                    Section {
                        gurmukhiKeyboardTip
                    }
                    .listRowBackground(Theme.raisedFill)
                }
            }
            .themedScreen()
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
                        save()
                    }
                    .disabled(!canSave)
                }
            }
        }
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

            Button {
                hasSeenGurmukhiKeyboardTip = true
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(.tertiary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss Gurmukhi keyboard tip")
        }
        .padding(.vertical, 4)
    }

    private func save() {
        guard canSave else { return }
        modelContext.insert(SavedArdaas(label: trimmedLabel, bentiText: trimmedBenti))
        dismiss()
    }
}

#Preview {
    let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
    let container = try! ModelContainer(for: SavedArdaas.self, configurations: configuration)
    return ComposeView()
        .modelContainer(container)
}
