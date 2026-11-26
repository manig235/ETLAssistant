import Foundation
import Testing
@testable import ETLKit

struct CatalogResponseLimitTests {
    private func stream(_ data: [UInt8]) -> AsyncStream<UInt8> {
        AsyncStream { continuation in
            data.forEach { continuation.yield($0) }
            continuation.finish()
        }
    }

    @Test func acceptsEmptySmallAndExactlyAtLimit() async throws {
        for size in [0, 3, 4] {
            let bytes = Array(repeating: UInt8(65), count: size)
            let result = try await CatalogClient.readResponse(stream(bytes), limit: 4)
            #expect(result == Data(bytes))
        }
    }

    @Test func oversizedResponseGivesSafeCatalogError() async {
        do {
            _ = try await CatalogClient.readResponse(stream([1, 2, 3, 4, 5]), limit: 4)
            Issue.record("Oversized response was accepted")
        } catch {
            #expect((error as? CatalogError)?.message == "수강편람 응답이 너무 커요")
        }
    }

    @Test func productionLimitIsOneMegabyte() {
        #expect(CatalogClient.MAX_RESPONSE_BYTES == 1024 * 1024)
    }
}
