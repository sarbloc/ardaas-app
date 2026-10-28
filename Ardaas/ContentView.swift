import SwiftData
import SwiftUI

/// Root view: Home's list of saved Ardaas beside the Reader (#172).
///
/// A split view throughout. On every iPhone, and the iPhone Duo folded, it
/// collapses to a stack: the list, and the Reader pushed over it. On the
/// open Duo the list is a sidebar that hides once an Ardaas opens, so the
/// Reader has the whole screen for its own layouts (#171); the system's
/// sidebar button brings the list back.
struct ContentView: View {
    /// The Ardaas open in the Reader, nil for none. Held here, not left to
    /// the list, so a Reader can replace itself with a duplicate (#159) and
    /// Back still goes to Home.
    @State private var selection: SavedArdaas?
    @State private var columnVisibility: NavigationSplitViewVisibility = .automatic

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
        .onChange(of: selection) { _, newSelection in
            // Reading wants the whole screen. Collapsed (compact width) the
            // visibility is ignored, so this only acts on the open Duo.
            guard newSelection != nil else { return }
            withAnimation {
                columnVisibility = .detailOnly
            }
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
