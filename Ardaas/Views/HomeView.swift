import SwiftData
import SwiftUI

/// Home screen: the list of saved Ardaas. Tap a row to read it,
/// swipe to delete, long-press for Duplicate and Delete, "+" (or the
/// empty-state button) to compose a new one.
struct HomeView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \SavedArdaas.createdAt, order: .reverse)
    private var savedArdaasList: [SavedArdaas]

    /// The navigation stack's path (`ContentView`): the Readers over Home.
    @Binding var path: [SavedArdaas]

    @State private var isComposing = false
    @State private var isShowingSources = false
    #if DEBUG
    @State private var isShowingBentiLab = false
    #endif

    var body: some View {
        Group {
            if savedArdaasList.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .themedScreen()
        .navigationTitle("Ardaas")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    isShowingSources = true
                } label: {
                    Label("About & Sources", systemImage: "info.circle")
                }
            }
            #if DEBUG
            // On-device translation diagnostics (#42) — debug builds only.
            // The shipping entry point for translation is #44, not this.
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    isShowingBentiLab = true
                } label: {
                    Label("Benti Lab", systemImage: "flask")
                }
            }
            #endif
            ToolbarItem(placement: .primaryAction) {
                Button {
                    isComposing = true
                } label: {
                    Label("Compose Ardaas", systemImage: "plus")
                }
            }
        }
        .sheet(isPresented: $isComposing) {
            ComposeView()
        }
        .sheet(isPresented: $isShowingSources) {
            SourcesView()
        }
        #if DEBUG
        .sheet(isPresented: $isShowingBentiLab) {
            BentiLabView()
        }
        #endif
    }

    private var list: some View {
        List {
            ForEach(savedArdaasList) { savedArdaas in
                NavigationLink(value: savedArdaas) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(savedArdaas.label)
                            .font(.headline)
                            .foregroundStyle(Theme.parchment)
                        Text(savedArdaas.createdAt,
                             format: .dateTime.day().month().year())
                            .font(.subheadline)
                            .foregroundStyle(Theme.mist)
                    }
                }
                .listRowBackground(Theme.raisedFill)
                .contextMenu {
                    Button {
                        duplicate(savedArdaas)
                    } label: {
                        Label("Duplicate", systemImage: "plus.square.on.square")
                    }
                    Button(role: .destructive) {
                        modelContext.delete(savedArdaas)
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
            .onDelete(perform: delete)
        }
        .navigationDestination(for: SavedArdaas.self) { savedArdaas in
            // Duplicating from the Reader opens the copy in its place, so
            // Back from the copy comes here rather than to the original.
            ReaderView(savedArdaas: savedArdaas) { copy in
                path = [copy]
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No saved Ardaas yet", systemImage: "text.book.closed")
        } description: {
            Text("Compose your first Ardaas with a personal benti.")
        } actions: {
            Button("Compose Ardaas") {
                isComposing = true
            }
            .buttonStyle(.borderedProminent)
        }
    }

    /// Saves a copy of `savedArdaas` (#159) and stays here: it leads the
    /// list, as the newest record.
    private func duplicate(_ savedArdaas: SavedArdaas) {
        let copy = savedArdaas.duplicate(existingLabels: savedArdaasList.map(\.label))
        withAnimation {
            modelContext.insert(copy)
        }
    }

    private func delete(at offsets: IndexSet) {
        for index in offsets {
            modelContext.delete(savedArdaasList[index])
        }
    }
}

#Preview {
    let container = try! ModelContainer(
        for: SavedArdaas.self,
        configurations: ModelConfiguration(isStoredInMemoryOnly: true)
    )
    container.mainContext.insert(
        SavedArdaas(label: "Morning Ardaas", bentiText: "A personal benti.")
    )
    container.mainContext.insert(
        SavedArdaas(label: "For the family", bentiText: "Another benti.")
    )
    return NavigationStack {
        HomeView(path: .constant([]))
    }
    .modelContainer(container)
}
