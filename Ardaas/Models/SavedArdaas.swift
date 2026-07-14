import Foundation
import SwiftData

/// A user-saved Ardaas: a labelled personal benti composed into the
/// canonical text at render time. Only the benti is persisted — the
/// canonical segments always come from the bundled content.
@Model
final class SavedArdaas {
    var label: String
    var bentiText: String
    var createdAt: Date
    /// Which Ardaas variant this benti composes into. Defaults to the
    /// standard SGPC text; pre-variant records migrate to it implicitly.
    var variantId: String = ArdaasLibrary.defaultVariantId

    init(
        label: String,
        bentiText: String,
        createdAt: Date = .now,
        variantId: String = ArdaasLibrary.defaultVariantId
    ) {
        self.label = label
        self.bentiText = bentiText
        self.createdAt = createdAt
        self.variantId = variantId
    }
}
