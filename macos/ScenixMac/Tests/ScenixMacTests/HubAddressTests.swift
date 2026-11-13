import XCTest
@testable import ScenixMac

final class HubAddressTests: XCTestCase {
    func testNormalizesOriginScopeIncludingEffectivePort() throws {
        let implicitHTTPS = try HubAddress.parse("HTTPS://Example.Test/")
        let explicitHTTPS = try HubAddress.parse("https://example.test:443")
        let customHTTP = try HubAddress.parse("http://example.test:8788")

        XCTAssertEqual(HubAddress.originKey(for: implicitHTTPS), "https://example.test:443")
        XCTAssertEqual(HubAddress.originKey(for: implicitHTTPS), HubAddress.originKey(for: explicitHTTPS))
        XCTAssertEqual(HubAddress.originKey(for: customHTTP), "http://example.test:8788")
        XCTAssertNotEqual(HubAddress.originKey(for: implicitHTTPS), HubAddress.originKey(for: customHTTP))
    }

    func testRejectsCredentialsEmbeddedInEndpoint() {
        XCTAssertThrowsError(try HubAddress.parse("https://viewer:secret@example.test")) { error in
            XCTAssertTrue(error.localizedDescription.contains("Do not put a username or password"))
        }
    }

    func testRejectsPathsThatCouldBePersistedAsAWebRoute() {
        XCTAssertThrowsError(try HubAddress.parse("https://example.test/settings"))
    }
}
