import AppKit
import SwiftUI
import WebKit

struct HubWebView: NSViewRepresentable {
    let endpoint: URL
    let token: String
    let reloadID: UUID
    @Binding var isLoading: Bool
    @Binding var errorMessage: String?

    func makeCoordinator() -> Coordinator {
        Coordinator(
            endpoint: endpoint,
            token: token,
            setLoading: { isLoading = $0 },
            setError: { errorMessage = $0 }
        )
    }

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.websiteDataStore = .default()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsMagnification = true
        webView.wantsLayer = true
        webView.layer?.backgroundColor = NSColor.black.cgColor
        context.coordinator.load(endpoint: endpoint, token: token, reloadID: reloadID, in: webView)
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        context.coordinator.update(endpoint: endpoint, token: token)
        if context.coordinator.lastReloadID != reloadID {
            context.coordinator.load(endpoint: endpoint, token: token, reloadID: reloadID, in: webView)
        }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        private var endpoint: URL
        private var token: String
        private let setLoading: (Bool) -> Void
        private let setError: (String?) -> Void
        fileprivate private(set) var lastReloadID: UUID?

        init(
            endpoint: URL,
            token: String,
            setLoading: @escaping (Bool) -> Void,
            setError: @escaping (String?) -> Void
        ) {
            self.endpoint = endpoint
            self.token = token
            self.setLoading = setLoading
            self.setError = setError
        }

        func update(endpoint: URL, token: String) {
            self.endpoint = endpoint
            self.token = token
        }

        func load(endpoint: URL, token: String, reloadID: UUID, in webView: WKWebView) {
            update(endpoint: endpoint, token: token)
            lastReloadID = reloadID
            setError(nil)
            setLoading(true)

            var request = URLRequest(url: endpoint)
            request.timeoutInterval = 20
            if let authorization = basicAuthorization(token: token) {
                request.setValue(authorization, forHTTPHeaderField: "Authorization")
            }
            webView.load(request)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation?) {
            setError(nil)
            setLoading(true)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation?) {
            setLoading(false)
        }

        func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation?,
            withError error: Error
        ) {
            setLoading(false)
            setError("The Hub web surface could not load: \(error.localizedDescription)")
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation?, withError error: Error) {
            setLoading(false)
            setError("The Hub web surface stopped loading: \(error.localizedDescription)")
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            setLoading(false)
            setError("The Hub web surface stopped unexpectedly. Reload it to continue.")
        }

        func webView(
            _ webView: WKWebView,
            didReceive challenge: URLAuthenticationChallenge,
            completionHandler: @escaping @MainActor @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
        ) {
            let space = challenge.protectionSpace
            guard space.authenticationMethod == NSURLAuthenticationMethodHTTPBasic,
                  sameOrigin(scheme: space.protocol, host: space.host, port: space.port),
                  !token.isEmpty else {
                completionHandler(.performDefaultHandling, nil)
                return
            }

            let credential = URLCredential(user: "scenix", password: token, persistence: .forSession)
            completionHandler(.useCredential, credential)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }

            // An iframe cannot replace the authenticated product surface. Let it follow the page's
            // own policy, but never feed it a credential unless its challenge matches this exact origin.
            if navigationAction.targetFrame?.isMainFrame == false {
                decisionHandler(.allow)
                return
            }

            // A nil target is `target=_blank`/a new window. Keep it out of the authenticated shell;
            // the system browser gives the user an explicit, separate destination instead.
            guard navigationAction.targetFrame?.isMainFrame == true else {
                openExternal(url)
                decisionHandler(.cancel)
                return
            }

            if sameOrigin(url: url) {
                decisionHandler(.allow)
            } else {
                // External destinations should not replace the Hub interface inside this authenticated shell.
                openExternal(url)
                decisionHandler(.cancel)
            }
        }

        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            // WebKit can ask its UI delegate directly for a target=_blank window. Returning nil keeps
            // the destination outside the embedded authenticated surface even if the navigation delegate
            // is bypassed by a future WebKit behavior change.
            guard let url = navigationAction.request.url else { return nil }
            if sameOrigin(url: url) {
                webView.load(navigationAction.request)
            } else {
                openExternal(url)
            }
            return nil
        }

        private func sameOrigin(url: URL) -> Bool {
            sameOrigin(
                scheme: url.scheme,
                host: url.host ?? "",
                port: url.port ?? defaultPort(for: url.scheme)
            )
        }

        private func sameOrigin(scheme: String?, host: String, port: Int) -> Bool {
            scheme?.lowercased() == endpoint.scheme?.lowercased()
                && host.caseInsensitiveCompare(endpoint.host ?? "") == .orderedSame
                && port == (endpoint.port ?? defaultPort(for: endpoint.scheme))
        }

        private func openExternal(_ url: URL) {
            guard let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme) else {
                setError("The Hub requested a link with an unsupported address type.")
                return
            }
            NSWorkspace.shared.open(url)
        }

        private func defaultPort(for scheme: String?) -> Int {
            scheme?.lowercased() == "https" ? 443 : 80
        }

        private func basicAuthorization(token: String) -> String? {
            guard !token.isEmpty else { return nil }
            let credentials = "scenix:\(token)"
            return "Basic \(Data(credentials.utf8).base64EncodedString())"
        }
    }
}
