import CoreGraphics

/// How the Reader arranges itself on the screen it has (#171).
///
/// Chosen from the size class and the window's shape, never the device
/// model, so it covers the iPhone Duo without naming it:
///
/// - **phone:** compact width. Every iPhone, and the Duo folded (its 5.4"
///   outer screen). One column, the floating layer pills (in the vertical
///   toolbar strip on the Duo, whose outer screen has one).
/// - **sideBySide:** regular width and wider than tall. The Duo open with the
///   fold vertical. Gurmukhi and transliteration on the left, English on the
///   right, the controls in the system's vertical strip on the right.
/// - **stacked:** regular width and taller than wide. The Duo open with the
///   fold horizontal. One column of readable width, a dock at the bottom.
///
/// Half folded (tabletop, or a half-open book) is deliberately not a layout:
/// the window keeps its size, so the choice doesn't change until the phone
/// is fully closed or opened.
enum ReaderLayout: Equatable {
    case phone
    case sideBySide
    case stacked

    /// `windowSize` is the whole window, safe areas included. A zero size
    /// (not measured yet) reads as a phone, the layout every device starts
    /// from.
    static func choose(isRegularWidth: Bool, windowSize: CGSize) -> ReaderLayout {
        guard isRegularWidth, windowSize.width > 0, windowSize.height > 0 else {
            return .phone
        }
        return windowSize.width > windowSize.height ? .sideBySide : .stacked
    }
}

/// The two column widths of the side-by-side Reader.
///
/// The gap between the columns straddles the fold, so no line of text sits
/// on the crease. That makes the columns unequal: the system's control strip takes
/// width from the right-hand half only.
struct ReaderColumns: Equatable {
    var left: CGFloat
    var right: CGFloat

    /// The gap between the columns, centred on the fold.
    static let gap: CGFloat = 48
    /// Narrower than this and a column stops reading as a column.
    static let minimumWidth: CGFloat = 200

    /// - Parameters:
    ///   - areaMinX: where the left column starts.
    ///   - areaMaxX: where the right column must end.
    ///   - foldX: the fold, in the same coordinate space.
    ///
    /// Falls back to two equal columns centred in the area when the fold
    /// would leave either column too narrow (the Reader not filling the
    /// window, say), so the layout degrades rather than breaks.
    static func split(
        areaMinX: CGFloat,
        areaMaxX: CGFloat,
        foldX: CGFloat,
        gap: CGFloat = gap,
        minimumWidth: CGFloat = minimumWidth
    ) -> ReaderColumns {
        let left = foldX - gap / 2 - areaMinX
        let right = areaMaxX - (foldX + gap / 2)
        if left >= minimumWidth, right >= minimumWidth {
            return ReaderColumns(left: left, right: right)
        }
        let even = max(0, (areaMaxX - areaMinX - gap) / 2)
        return ReaderColumns(left: even, right: even)
    }
}
