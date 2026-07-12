import SwiftUI

/// Placeholder root view. Replaced by the Home screen (issue #4).
struct ContentView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("ੴ")
                .font(.system(size: 44))
            Text("ਅਰਦਾਸ")
                .font(.largeTitle.weight(.semibold))
            Text("Ardaas")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
