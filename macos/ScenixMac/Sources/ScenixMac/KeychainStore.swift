import Foundation
import Security

enum KeychainStore {
    private static let service = "com.scenix.mac.preview"
    private static let accountPrefix = "hub-basic-token:"
    // Releases before origin-scoping used one shared account. It is deliberately deleted on first
    // startup of this version rather than migrated: associating it with whichever host is typed next
    // would repeat the credential-disclosure bug this scope prevents.
    private static let legacyAccount = "hub-basic-token"

    static func readToken(for origin: String) throws -> String? {
        var query = baseQuery(for: origin)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.status(status) }
        guard let data = item as? Data, let token = String(data: data, encoding: .utf8) else {
            throw KeychainError.unreadableValue
        }
        return token
    }

    static func saveToken(_ token: String, for origin: String) throws {
        let data = Data(token.utf8)
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(baseQuery(for: origin) as CFDictionary, update as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw KeychainError.status(status) }

        var insert = baseQuery(for: origin)
        insert[kSecValueData as String] = data
        let addStatus = SecItemAdd(insert as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw KeychainError.status(addStatus) }
    }

    static func deleteToken(for origin: String) throws {
        let status = SecItemDelete(baseQuery(for: origin) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.status(status)
        }
    }

    /** Forgetting this Mac removes every preview token, not merely the endpoint currently in the field. */
    static func deleteAllTokens() throws {
        let status = SecItemDelete(serviceQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.status(status)
        }
    }

    static func deleteLegacyToken() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: legacyAccount,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.status(status)
        }
    }

    private static var serviceQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
    }

    private static func baseQuery(for origin: String) -> [String: Any] {
        var query = serviceQuery
        query[kSecAttrAccount as String] = accountPrefix + origin
        return query
    }
}

private enum KeychainError: LocalizedError {
    case unreadableValue
    case status(OSStatus)

    var errorDescription: String? {
        switch self {
        case .unreadableValue:
            return "The saved access token could not be read."
        case let .status(status):
            let message = SecCopyErrorMessageString(status, nil) as String? ?? "Keychain error \(status)"
            return message
        }
    }
}
