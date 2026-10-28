import SwiftData
import SwiftUI

/// Root view: hosts the Home screen in the app's navigation stack.
struct ContentView: View {
    /// The Readers pushed over Home. Held here, not left to the stack, so a
    /// Reader can replace itself with a duplicate (#159) and Back still goes
    /// to Home.
    @State private var path: [SavedArdaas] = []
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(path: $path)
        }
        // TEMP (#171 screenshots, dropped before merge): open a sample
        // Reader straight away for the UI test.
        .onAppear {
            guard ProcessInfo.processInfo.arguments.contains("-duoShots"), path.isEmpty else { return }
            let sample = SavedArdaas(
                label: "Morning Ardaas",
                bentiText: "Please bless the family with health and chardi kala.",
                bentiGurmukhi: "ਪਰਿਵਾਰ ਨੂੰ ਤੰਦਰੁਸਤੀ ਅਤੇ ਚੜ੍ਹਦੀ ਕਲਾ ਬਖ਼ਸ਼ੋ ਜੀ।",
                bentiTransliteration: "parivaar noo(n) tandarustee ate charhdee kalaa bakhsho jee.",
                occasion: .catalog(id: "birthday"),
                paathId: "japji-sahib"
            )
            modelContext.insert(sample)
            path = [sample]
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
