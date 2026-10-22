import SwiftData
import XCTest
@testable import Ardaas

/// Duplicating a saved Ardaas (#159): the copy's label, and the record it
/// makes.
@MainActor
final class DuplicateArdaasTests: XCTestCase {
    // MARK: - Label

    private struct ParityFixture: Decodable {
        struct Case: Decodable {
            let name: String
            let label: String
            let existing: [String]
            let expected: String
        }
        let cases: [Case]
    }

    /// The Android port asserts the same file
    /// (`Fixtures/duplicate-label-parity.json`), so the two apps name a copy
    /// identically. Never edit an expected value to make one side pass.
    func testSharedParityFixture() throws {
        let url = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "duplicate-label-parity", withExtension: "json"),
            "duplicate-label-parity.json missing from test bundle"
        )
        let fixture = try JSONDecoder().decode(ParityFixture.self, from: Data(contentsOf: url))
        XCTAssertGreaterThanOrEqual(fixture.cases.count, 30)
        for testCase in fixture.cases {
            let label = DuplicateLabel.label(forCopyOf: testCase.label, existing: testCase.existing)
            // Scalar for scalar: Swift compares Strings by canonical
            // equivalence, Kotlin by code unit, and the fixture pins the text.
            XCTAssertTrue(
                label.unicodeScalars.elementsEqual(testCase.expected.unicodeScalars),
                "\(testCase.name): \(label)"
            )
        }
    }

    func testDuplicatingRepeatedlyCountsUp() {
        var labels = ["Family"]
        for _ in 0..<4 {
            labels.append(DuplicateLabel.label(forCopyOf: labels.last!, existing: labels))
        }
        XCTAssertEqual(labels, ["Family", "Family copy", "Family copy 2", "Family copy 3", "Family copy 4"])
    }

    func testTheBaseStripsOnlyACopySuffix() {
        XCTAssertEqual(DuplicateLabel.base(of: " Family copy 12 "), "Family")
        XCTAssertEqual(DuplicateLabel.base(of: "Family COPY"), "Family")
        XCTAssertEqual(DuplicateLabel.base(of: "Family copy copy"), "Family copy")
        XCTAssertEqual(DuplicateLabel.base(of: "Family"), "Family")
        XCTAssertEqual(DuplicateLabel.base(of: "copy"), "copy")
    }

    // MARK: - Record

    /// A record with every user field set away from its default — the
    /// occasion pair and paath deliberately in a shape `init` and `apply`
    /// would normalise, so a copy made through them would differ.
    private func fullRecord() -> SavedArdaas {
        let record = SavedArdaas(
            label: "Family",
            bentiText: "Bless the sangat.",
            createdAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
        record.variantId = "buddha-dal"
        record.bentiGurmukhi = "ਸਿੰਘ ਅਰਦਾਸ"
        record.bentiTransliteration = "Singh Ardaas (older rules)"
        record.occasionId = "japji-sahib"
        record.occasionCustom = "stale free text"
        record.paathId = " sukhmani-sahib "
        return record
    }

    /// Every stored field, listed by hand: a field added to `SavedArdaas`
    /// must be added here and to `duplicate(label:createdAt:)` together.
    func testEveryFieldIsCopiedAsStored() {
        let source = fullRecord()
        let createdAt = Date(timeIntervalSince1970: 1_800_000_000)
        let copy = source.duplicate(label: "Family copy", createdAt: createdAt)

        XCTAssertEqual(copy.label, "Family copy")
        XCTAssertEqual(copy.createdAt, createdAt)
        XCTAssertEqual(copy.bentiText, source.bentiText)
        XCTAssertEqual(copy.variantId, source.variantId)
        XCTAssertEqual(copy.bentiGurmukhi, source.bentiGurmukhi)
        XCTAssertEqual(copy.bentiTransliteration, source.bentiTransliteration)
        XCTAssertEqual(copy.occasionId, source.occasionId)
        XCTAssertEqual(copy.occasionCustom, source.occasionCustom)
        XCTAssertEqual(copy.paathId, source.paathId)
        XCTAssertFalse(copy === source)
    }

    func testTheSourceIsUntouched() {
        let source = fullRecord()
        _ = source.duplicate(label: "Family copy")
        XCTAssertEqual(source.label, "Family")
        XCTAssertEqual(source.createdAt, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(source.paathId, " sukhmani-sahib ")
    }

    func testTheCopyIsNamedAgainstEverySavedLabel() {
        let copy = fullRecord().duplicate(existingLabels: ["Family", "family copy"])
        XCTAssertEqual(copy.label, "Family copy 2")
    }

    /// Through SwiftData: a second record with its own identity, every field
    /// equal but the label and `createdAt`, and the original unchanged.
    func testDuplicatingInsertsASecondRecord() throws {
        let container = try ModelContainer(
            for: SavedArdaas.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let source = fullRecord()
        context.insert(source)
        try context.save()

        let labels = try context.fetch(FetchDescriptor<SavedArdaas>()).map(\.label)
        let createdAt = Date(timeIntervalSince1970: 1_800_000_000)
        context.insert(source.duplicate(existingLabels: labels, createdAt: createdAt))
        try context.save()

        let all = try context.fetch(FetchDescriptor<SavedArdaas>(sortBy: [SortDescriptor(\.createdAt)]))
        XCTAssertEqual(all.count, 2)
        let (original, copy) = (all[0], all[1])
        XCTAssertNotEqual(original.persistentModelID, copy.persistentModelID)
        XCTAssertEqual(original.label, "Family")
        XCTAssertEqual(copy.label, "Family copy")
        XCTAssertEqual(copy.createdAt, createdAt)
        XCTAssertEqual(copy.bentiText, original.bentiText)
        XCTAssertEqual(copy.variantId, original.variantId)
        XCTAssertEqual(copy.bentiGurmukhi, original.bentiGurmukhi)
        XCTAssertEqual(copy.bentiTransliteration, original.bentiTransliteration)
        XCTAssertEqual(copy.occasionId, original.occasionId)
        XCTAssertEqual(copy.occasionCustom, original.occasionCustom)
        XCTAssertEqual(copy.paathId, original.paathId)
    }
}
