import SwiftUI

/// Brand palette derived from the app icon: a deep navy field with a kesri
/// (saffron) accent. The app runs dark by design — the icon is the theme.
///
/// Kesri is also defined in the asset catalog as `AccentColor`, so
/// `Color.accentColor` and default control tints resolve to it everywhere.
enum Theme {
    // MARK: Surfaces (the icon's gradient)
    static let navyTop = Color(red: 24 / 255.0, green: 38 / 255.0, blue: 74 / 255.0) // #18264A
    static let navyBottom = Color(red: 12 / 255.0, green: 19 / 255.0, blue: 40 / 255.0) // #0C1328

    /// Kesri accent — mirrors the asset-catalog AccentColor. #FF9933
    static let kesri = Color(red: 255 / 255.0, green: 153 / 255.0, blue: 51 / 255.0)

    // MARK: Reading layers
    /// Gurmukhi — warm parchment white. #F2EADB
    static let parchment = Color(red: 242 / 255.0, green: 234 / 255.0, blue: 219 / 255.0)
    /// Transliteration — muted sand gold. #D9A868
    static let sand = Color(red: 217 / 255.0, green: 168 / 255.0, blue: 104 / 255.0)
    /// Translation — cool mist blue-grey. #93A0BC
    static let mist = Color(red: 147 / 255.0, green: 160 / 255.0, blue: 188 / 255.0)

    /// Row/card fill that reads as "raised" on the navy gradient.
    static let raisedFill = Color.white.opacity(0.06)

    static var background: LinearGradient {
        LinearGradient(
            colors: [navyTop, navyBottom],
            startPoint: .top,
            endPoint: .bottom
        )
    }
}

/// Puts the brand gradient behind a screen and hides the default scroll
/// container background so List/Form/ScrollView sit on navy.
private struct ThemedScreen: ViewModifier {
    func body(content: Content) -> some View {
        content
            .scrollContentBackground(.hidden)
            .background(Theme.background.ignoresSafeArea())
    }
}

extension View {
    func themedScreen() -> some View {
        modifier(ThemedScreen())
    }
}
