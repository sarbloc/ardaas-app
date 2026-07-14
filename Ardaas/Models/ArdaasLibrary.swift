import Foundation

/// One Ardaas variant: identity plus its full text content.
struct ArdaasVariant: Equatable, Identifiable {
    let id: String
    let displayName: String
    let content: ArdaasContent
}

/// The bundled collection of Ardaas variants, driven by
/// `Resources/variants.json`. Manifest order is DISPLAY order only —
/// the default variant is pinned to `defaultVariantId` by policy, so
/// reordering the manifest can never silently change what new saves or
/// unknown-id fallbacks resolve to.
struct ArdaasLibrary: Equatable {
    let variants: [ArdaasVariant]

    /// The id every new SavedArdaas starts with, and the fallback for
    /// records whose variant has been removed from the bundle. Pinned —
    /// not derived from manifest order (see type comment). `validate()`
    /// rejects any bundle that omits it.
    static let defaultVariantId = "sgpc"

    var defaultVariant: ArdaasVariant {
        // validate() guarantees defaultVariantId exists; variants[0] is
        // unreachable and exists only to keep this non-optional.
        variants.first { $0.id == Self.defaultVariantId } ?? variants[0]
    }

    func variant(id: String) -> ArdaasVariant? {
        variants.first { $0.id == id }
    }

    /// Resolves a saved record's variant, falling back to the default
    /// when the id is unknown (e.g. a variant later removed).
    func resolvedVariant(id: String) -> ArdaasVariant {
        variant(id: id) ?? defaultVariant
    }
}

enum ArdaasLibraryError: Error, Equatable {
    case manifestMissing
    case noVariants
    case duplicateVariantIds
    case missingDefaultVariant
    case variantResourceMissing(id: String, resource: String)
}

extension ArdaasLibrary {
    private struct Manifest: Codable {
        struct Entry: Codable {
            let id: String
            let displayName: String
            let resource: String
        }

        let version: Int
        let variants: [Entry]
    }

    /// Loads and validates the manifest and every variant it lists.
    /// Throws — a broken bundle is a build defect, not a recoverable
    /// runtime state.
    static func loadBundled(from bundle: Bundle = .appModule) throws -> ArdaasLibrary {
        guard let url = bundle.url(forResource: "variants", withExtension: "json") else {
            throw ArdaasLibraryError.manifestMissing
        }
        let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: url))

        let variants: [ArdaasVariant] = try manifest.variants.map { entry in
            guard let contentURL = bundle.url(forResource: entry.resource, withExtension: "json") else {
                throw ArdaasLibraryError.variantResourceMissing(id: entry.id, resource: entry.resource)
            }
            let content = try JSONDecoder().decode(ArdaasContent.self, from: Data(contentsOf: contentURL))
            try content.validate()
            return ArdaasVariant(id: entry.id, displayName: entry.displayName, content: content)
        }

        let library = ArdaasLibrary(variants: variants)
        try library.validate()
        return library
    }

    func validate() throws {
        guard !variants.isEmpty else {
            throw ArdaasLibraryError.noVariants
        }
        let ids = variants.map(\.id)
        guard Set(ids).count == ids.count else {
            throw ArdaasLibraryError.duplicateVariantIds
        }
        guard ids.contains(Self.defaultVariantId) else {
            throw ArdaasLibraryError.missingDefaultVariant
        }
    }
}

private final class BundleToken {}

private extension Bundle {
    /// The bundle this code is compiled into (the app target) — resource
    /// lookup works identically in the app and in hosted/unhosted tests.
    /// Named `appModule` to avoid colliding with SwiftPM's generated
    /// `Bundle.module` if this code ever moves into a package.
    static var appModule: Bundle { Bundle(for: BundleToken.self) }
}
