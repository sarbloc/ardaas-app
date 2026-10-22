import SwiftData
import SwiftUI

/// Root view: hosts the Home screen in the app's navigation stack.
struct ContentView: View {
    /// The Readers pushed over Home. Held here, not left to the stack, so a
    /// Reader can replace itself with a duplicate (#159) and Back still goes
    /// to Home.
    @State private var path: [SavedArdaas] = []

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(path: $path)
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
