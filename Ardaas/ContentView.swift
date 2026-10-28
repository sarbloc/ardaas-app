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

    /// Reading wants the whole screen; with nothing open, the list is the
    /// screen. Collapsed (compact width) the split view ignores this, so it
    /// only acts on the open Duo.
    private static func visibility(selection: SavedArdaas?) -> NavigationSplitViewVisibility {
        selection == nil ? .all : .detailOnly
    }

    /// Wide enough for Home's toolbar (About, +, the sidebar button) and
    /// its title.
    private static let listWidth: CGFloat = 360

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            HomeView(selection: $selection)
                .navigationSplitViewColumnWidth(Self.listWidth)
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
                // The list slides over this screen; keep the prompt clear
                // of it.
                .padding(.leading, columnVisibility == .detailOnly ? 0 : Self.listWidth)
                .themedScreen()
            }
        }
        // The list slides over the Reader rather than squeezing it, so the
        // Reader's own layouts (#171) always measure the whole screen.
        .navigationSplitViewStyle(.prominentDetail)
        .onChange(of: selection) {
            settleColumns()
        }
        // Unfolding expands the split view, and it brings the list back
        // over an open Reader; put it away again.
        .onChange(of: horizontalSizeClass) {
            settleColumns()
        }
    }

    /// Shows or puts away the list for what's open. Set twice: the split
    /// view applies its own visibility as it first lays out or expands
    /// (unfolding), after a change made in the same update. In the iOS 27.1
    /// simulator it reset `.detailOnly` to `.all` ~140ms later, putting the
    /// list back over the Reader; the second set, well after that, wins.
    /// Both sets follow the current selection, so a late one can't undo a
    /// newer choice.
    private func settleColumns() {
        withAnimation {
            columnVisibility = Self.visibility(selection: selection)
        }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(400))
            withAnimation {
                columnVisibility = Self.visibility(selection: selection)
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
