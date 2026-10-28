import SwiftData
import SwiftUI

/// Root view: Home's list of saved Ardaas beside the Reader (#172).
///
/// A split view throughout. On every iPhone, and the iPhone Duo folded, it
/// collapses to a stack: the list, and the Reader pushed over it. On the
/// open Duo the list is a sidebar over the Reader: showing when nothing is
/// open, put away once an Ardaas opens, so the Reader has the whole screen
/// for its own layouts (#171). The system's sidebar button brings it back.
struct ContentView: View {
    /// The Ardaas open in the Reader, nil for none. Held here, not left to
    /// the list, so a Reader can replace itself with a duplicate (#159) and
    /// Back still goes to Home.
    @State private var selection: SavedArdaas?
    /// Starts with the list showing: on the open Duo the system's default
    /// hides it, leaving only the empty Reader placeholder.
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.modelContext) private var modelContext

    /// Reading wants the whole screen; with nothing open, the list is the
    /// screen. Collapsed (compact width) the split view ignores this, so it
    /// only acts on the open Duo.
    private static func visibility(selection: SavedArdaas?) -> NavigationSplitViewVisibility {
        selection == nil ? .all : .detailOnly
    }

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            HomeView(selection: $selection)
        } detail: {
            if let selection {
                ReaderView(savedArdaas: selection) { copy in
                    self.selection = copy
                }
                // A different record is a different Reader: its loaded
                // text, layer state and reading place start afresh.
                .id(selection.persistentModelID)
            } else {
                ContentUnavailableView(
                    "Choose an Ardaas",
                    systemImage: "text.book.closed",
                    description: Text("Pick one from the list to read it.")
                )
                .themedScreen()
            }
        }
        // TEMP (#172 screenshots, dropped before merge): seed samples once;
        // `-duoShots reader` opens the first.
        .onAppear {
            let args = ProcessInfo.processInfo.arguments
            guard let i = args.firstIndex(of: "-duoShots"), selection == nil else { return }
            var all = (try? modelContext.fetch(FetchDescriptor<SavedArdaas>())) ?? []
            if all.isEmpty {
                let sample = SavedArdaas(
                    label: "Morning Ardaas",
                    bentiText: "Please bless the family with health and chardi kala.",
                    bentiGurmukhi: "ਪਰਿਵਾਰ ਨੂੰ ਤੰਦਰੁਸਤੀ ਅਤੇ ਚੜ੍ਹਦੀ ਕਲਾ ਬਖ਼ਸ਼ੋ ਜੀ।",
                    bentiTransliteration: "parivaar noo(n) tandarustee ate charhdee kalaa bakhsho jee.",
                    occasion: .catalog(id: "birthday"),
                    paathId: "japji-sahib"
                )
                modelContext.insert(SavedArdaas(label: "For the family", bentiText: "Another benti."))
                modelContext.insert(SavedArdaas(label: "Gurpurab", bentiText: "A third benti."))
                modelContext.insert(sample)
                try? modelContext.save()
                all = [sample]
            }
            if args.indices.contains(i + 1), args[i + 1] == "reader" {
                selection = all.first { $0.label == "Morning Ardaas" } ?? all.first
            }
        }
        // The list slides over the Reader rather than squeezing it, so the
        // Reader's own layouts (#171) always measure the whole screen.
        .navigationSplitViewStyle(.prominentDetail)
        .onChange(of: selection) {
            withAnimation {
                columnVisibility = Self.visibility(selection: selection)
            }
        }
        // Unfolding expands the split view, and it brings the list back
        // over an open Reader; put it away again.
        .onChange(of: horizontalSizeClass) {
            columnVisibility = Self.visibility(selection: selection)
        }
    }
}

#Preview {
    ContentView()
        .modelContainer(
            try! ModelContainer(
                for: SavedArdaas.self,
                configurations: ModelConfiguration(isStoredInMemoryOnly: true)
            )
        )
}
