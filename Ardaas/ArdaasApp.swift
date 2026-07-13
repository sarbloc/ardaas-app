import SwiftData
import SwiftUI

@main
struct ArdaasApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // The brand theme is a dark navy field (see Theme.swift);
                // the app is dark by design, not by system setting.
                .preferredColorScheme(.dark)
        }
        .modelContainer(for: SavedArdaas.self)
    }
}
