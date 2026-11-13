import Combine
import Foundation

@MainActor
final class HubSession: ObservableObject {
    enum Phase: Equatable {
        case onboarding
        case validating
        case connected
    }

    @Published private(set) var phase: Phase = .onboarding
    @Published var draftEndpoint: String {
        didSet {
            // A text field can change from one complete URL to another without asking the connection
            // routine first. Re-scope or clear the visible token at that point so it can never ride to
            // the next host as an accidental Basic credential.
            refreshDraftTokenForEndpoint()
        }
    }
    @Published var draftToken: String
    @Published private(set) var activeEndpoint: URL?
    @Published private(set) var activeToken = ""
    @Published private(set) var health: HubHealth?
    @Published private(set) var statusMessage: String?
    @Published private(set) var errorMessage: String?
    @Published private(set) var storageWarning: String?
    @Published private(set) var webReloadID = UUID()

    private static let endpointKey = "ScenixMac.hubEndpoint"
    private let defaults: UserDefaults
    private var didRestore = false
    /** The normalized origin to which [draftToken] belongs; nil means it must never be sent. */
    private var draftTokenOrigin: String?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        draftEndpoint = defaults.string(forKey: Self.endpointKey) ?? ""
        draftToken = ""
        draftTokenOrigin = nil
        // Do not migrate the prior global token. The correct origin cannot be reconstructed safely.
        try? KeychainStore.deleteLegacyToken()
        refreshDraftTokenForEndpoint()
    }

    var isBusy: Bool {
        phase == .validating
    }

    var isConnected: Bool {
        phase == .connected && activeEndpoint != nil
    }

    var hasSavedHub: Bool {
        !draftEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func restoreAndValidateIfAvailable() async {
        guard !didRestore else { return }
        didRestore = true
        guard hasSavedHub else { return }
        await connect()
    }

    func reconnect() async {
        await connect()
    }

    func connect() async {
        errorMessage = nil
        storageWarning = nil

        let rawEndpoint = draftEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            let endpoint = try HubAddress.parse(rawEndpoint)
            let origin = HubAddress.originKey(for: endpoint)
            // Defend against a programmatic endpoint replacement that bypassed the field observer.
            // A token with no exact origin proof is always discarded before a request is constructed.
            if draftTokenOrigin != origin {
                refreshDraftToken(for: endpoint)
            }
            let token = draftTokenOrigin == origin
                ? draftToken.trimmingCharacters(in: .whitespacesAndNewlines) : ""
            phase = .validating
            statusMessage = "Checking \(endpoint.host(percentEncoded: false) ?? endpoint.absoluteString)…"

            let result = try await HubHealthClient.validate(endpoint: endpoint, token: token)

            activeEndpoint = endpoint
            activeToken = token
            health = result
            defaults.set(endpoint.absoluteString, forKey: Self.endpointKey)
            draftEndpoint = endpoint.absoluteString

            do {
                if token.isEmpty {
                    try KeychainStore.deleteToken(for: origin)
                } else {
                    try KeychainStore.saveToken(token, for: origin)
                }
            } catch {
                storageWarning = "Connected for this session, but the access token could not be saved securely."
            }

            statusMessage = result.summary
            phase = .connected
            webReloadID = UUID()
        } catch {
            activeEndpoint = nil
            activeToken = ""
            health = nil
            statusMessage = nil
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            phase = .onboarding
        }
    }

    func showOnboarding() {
        activeEndpoint = nil
        activeToken = ""
        health = nil
        errorMessage = nil
        statusMessage = nil
        phase = .onboarding
    }

    func forgetSavedHub() {
        defaults.removeObject(forKey: Self.endpointKey)
        do {
            try KeychainStore.deleteAllTokens()
        } catch {
            storageWarning = "The saved access token could not be removed from Keychain."
        }
        draftEndpoint = ""
        draftToken = ""
        activeEndpoint = nil
        activeToken = ""
        health = nil
        errorMessage = nil
        statusMessage = nil
        phase = .onboarding
        webReloadID = UUID()
    }

    func reloadWebSurface() {
        guard isConnected else { return }
        webReloadID = UUID()
    }

    /** Read only a credential explicitly saved for this endpoint's normalized origin. */
    private func refreshDraftTokenForEndpoint() {
        guard let endpoint = try? HubAddress.parse(draftEndpoint) else {
            draftTokenOrigin = nil
            draftToken = ""
            return
        }
        refreshDraftToken(for: endpoint)
    }

    private func refreshDraftToken(for endpoint: URL) {
        let origin = HubAddress.originKey(for: endpoint)
        guard draftTokenOrigin != origin else { return }
        draftTokenOrigin = origin
        draftToken = ""
        do {
            draftToken = try KeychainStore.readToken(for: origin) ?? ""
        } catch {
            storageWarning = "A saved access token could not be read from Keychain. Enter it again if this Hub requires one."
        }
    }
}

struct HubHealth: Sendable, Equatable {
    let hubName: String
    let version: String?

    var summary: String {
        if let version, !version.isEmpty {
            return "Connected to \(hubName) · \(version)"
        }
        return "Connected to \(hubName)"
    }
}

enum HubAddress {
    static func parse(_ raw: String) throws -> URL {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw HubConnectionError.invalidAddress("Enter the Hub address, for example http://192.168.0.24:8788.")
        }

        let withScheme = trimmed.contains("://") ? trimmed : "http://\(trimmed)"
        guard var components = URLComponents(string: withScheme),
              let scheme = components.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              components.host != nil else {
            throw HubConnectionError.invalidAddress("Use an HTTP or HTTPS Hub address with a host name or IP address.")
        }

        guard components.user == nil && components.password == nil else {
            throw HubConnectionError.invalidAddress("Do not put a username or password in the Hub address. Enter the access token in its dedicated field.")
        }

        let path = components.percentEncodedPath
        guard path.isEmpty || path == "/" else {
            throw HubConnectionError.invalidAddress("Use the Hub address without a page path. The desktop preview opens the Hub home page itself.")
        }

        components.percentEncodedPath = ""
        components.query = nil
        components.fragment = nil
        guard let url = components.url else {
            throw HubConnectionError.invalidAddress("That Hub address could not be understood.")
        }
        return url
    }

    /** A credential scope includes scheme and effective port: HTTP and HTTPS never share a token. */
    static func originKey(for endpoint: URL) -> String {
        let scheme = endpoint.scheme?.lowercased() ?? "http"
        let host = endpoint.host(percentEncoded: false)?.lowercased() ?? ""
        let port = endpoint.port ?? defaultPort(for: scheme)
        return "\(scheme)://\(host):\(port)"
    }

    private static func defaultPort(for scheme: String) -> Int {
        scheme.lowercased() == "https" ? 443 : 80
    }
}

private enum HubHealthClient {
    static func validate(endpoint: URL, token: String) async throws -> HubHealth {
        let healthURL = endpoint.appending(path: "healthz")
        var request = URLRequest(url: healthURL)
        request.httpMethod = "GET"
        request.timeoutInterval = 8
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let authorization = basicAuthorization(token: token) {
            request.setValue(authorization, forHTTPHeaderField: "Authorization")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw HubConnectionError.invalidHealth("The Hub did not return an HTTP response.")
        }
        if http.statusCode == 401 {
            throw HubConnectionError.authenticationRequired
        }
        guard (200 ... 299).contains(http.statusCode) else {
            throw HubConnectionError.httpStatus(http.statusCode)
        }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw HubConnectionError.invalidHealth("The Hub health check returned invalid JSON.")
        }

        let status = (object["status"] as? String)?.lowercased()
        let ok = object["ok"] as? Bool
        guard status == "ok" || ok == true else {
            throw HubConnectionError.invalidHealth("The address answered, but it did not identify itself as a healthy Hub.")
        }

        // `/healthz` deliberately stays public so televisions can discover a protected Hub. A successful
        // health answer alone therefore cannot prove that this Mac can open the actual web surface.
        // Check the root with the same optional Basic credential before handing the URL to WebKit.
        var webRequest = URLRequest(url: endpoint)
        webRequest.httpMethod = "HEAD"
        webRequest.timeoutInterval = 8
        if let authorization = basicAuthorization(token: token) {
            webRequest.setValue(authorization, forHTTPHeaderField: "Authorization")
        }
        let (_, webResponse) = try await URLSession.shared.data(for: webRequest)
        guard let webHTTP = webResponse as? HTTPURLResponse else {
            throw HubConnectionError.invalidHealth("The Hub web surface did not return an HTTP response.")
        }
        if webHTTP.statusCode == 401 {
            throw HubConnectionError.authenticationRequired
        }
        guard (200 ... 299).contains(webHTTP.statusCode) else {
            throw HubConnectionError.webSurfaceStatus(webHTTP.statusCode)
        }

        let name = (object["hub"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let version = (object["version"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return HubHealth(hubName: (name?.isEmpty == false ? name! : "Scenix Hub"), version: version)
    }

    static func basicAuthorization(token: String) -> String? {
        guard !token.isEmpty else { return nil }
        let credentials = "scenix:\(token)"
        let encoded = Data(credentials.utf8).base64EncodedString()
        return "Basic \(encoded)"
    }
}

private enum HubConnectionError: LocalizedError {
    case invalidAddress(String)
    case authenticationRequired
    case httpStatus(Int)
    case webSurfaceStatus(Int)
    case invalidHealth(String)

    var errorDescription: String? {
        switch self {
        case let .invalidAddress(message), let .invalidHealth(message):
            return message
        case .authenticationRequired:
            return "This Hub requires an access token. Enter the token from the Hub owner and try again."
        case let .httpStatus(code):
            return "The Hub health check returned HTTP \(code). Check the address, network, and Hub status."
        case let .webSurfaceStatus(code):
            return "The Hub is healthy, but its web surface returned HTTP \(code). Check the Hub configuration and access token."
        }
    }
}
