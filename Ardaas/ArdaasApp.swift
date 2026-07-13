import SwiftData
import SwiftUI

@main
struct ArdaasApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(for: SavedArdaas.self)
    }
}
