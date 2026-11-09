import SwiftUI

struct PairingView: View {
    @ObservedObject var hub: HubSession
    @FocusState private var focusedField: Field?

    private enum Field {
        case endpoint
        case token
    }

    var body: some View {
        ZStack {
            ScenixPalette.background.ignoresSafeArea()
            LinearGradient(
                colors: [ScenixPalette.blueGlow.opacity(0.20), .clear, ScenixPalette.red.opacity(0.10)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 30) {
                    header
                    connectionOptions

                    Text("The catalog, profiles, selected theme, add-ons, and playback behavior remain owned by the Hub.")
                        .font(.callout)
                        .foregroundStyle(ScenixPalette.secondaryText)
                }
                .padding(48)
                .frame(maxWidth: 1120, alignment: .leading)
            }
        }
        .onAppear {
            if hub.draftEndpoint.isEmpty {
                focusedField = .endpoint
            }
        }
    }

    /**
     * The app can open at 960 points. Its content width after safe gutters is intentionally too narrow
     * for the two full panels, so the compact layout stacks rather than silently compressing fields and
     * help text. Wider desktop windows retain the side-by-side information scan.
     */
    @ViewBuilder
    private var connectionOptions: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 28) {
                connectionForm
                    .frame(minWidth: 500, maxWidth: 610, alignment: .leading)
                limits
                    .frame(minWidth: 330, maxWidth: 390, alignment: .leading)
            }
            .frame(minWidth: 920, alignment: .leading)

            VStack(alignment: .leading, spacing: 24) {
                connectionForm
                limits
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Scenix")
                .font(.system(size: 42, weight: .bold, design: .rounded))
                .foregroundStyle(.white)

            Text("Connect this Mac to your Hub")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)

            Text("Enter the address of a Hub you operate. The desktop preview checks it before loading the exact web interface the Hub serves.")
                .font(.body)
                .foregroundStyle(ScenixPalette.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private var connectionForm: some View {
        VStack(alignment: .leading, spacing: 20) {
            Label("Hub connection", systemImage: "server.rack")
                .font(.headline)
                .foregroundStyle(.white)

            VStack(alignment: .leading, spacing: 8) {
                Text("Hub address")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                TextField("http://192.168.0.24:8788", text: $hub.draftEndpoint)
                    .textFieldStyle(.roundedBorder)
                    .focused($focusedField, equals: .endpoint)
                    .accessibilityHint("Enter the root address of your Hub. A port is optional if your Hub uses the standard web port.")
                    .onSubmit {
                        Task { await hub.connect() }
                    }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Access token (optional)")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                SecureField("Only needed when the Hub uses access control", text: $hub.draftToken)
                    .textFieldStyle(.roundedBorder)
                    .focused($focusedField, equals: .token)
                    .accessibilityHint("The token is saved only in macOS Keychain after a successful connection.")
                    .onSubmit {
                        Task { await hub.connect() }
                    }
                Text("If your Hub requires HTTP Basic access, this value is used as the password and is stored in Keychain, never in UserDefaults.")
                    .font(.caption)
                    .foregroundStyle(ScenixPalette.secondaryText)
                    .fixedSize(horizontal: false, vertical: true)
            }

            status

            HStack(spacing: 12) {
                Button {
                    Task { await hub.connect() }
                } label: {
                    Label(hub.isBusy ? "Checking Hub…" : "Test and open Hub", systemImage: "arrow.right.circle.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(ScenixPalette.red)
                .controlSize(.large)
                .keyboardShortcut(.defaultAction)
                .disabled(hub.draftEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || hub.isBusy)
                .accessibilityHint("Runs the Hub health check, then opens the Hub interface when it succeeds.")

                if hub.hasSavedHub {
                    Button("Forget saved Hub") {
                        hub.forgetSavedHub()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .disabled(hub.isBusy)
                }
            }
        }
        .padding(28)
        .frame(maxWidth: 610, alignment: .leading)
        .background(ScenixPalette.panel, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(ScenixPalette.border, lineWidth: 1)
        }
    }

    @ViewBuilder
    private var status: some View {
        if hub.isBusy {
            HStack(spacing: 10) {
                ProgressView()
                    .controlSize(.small)
                Text(hub.statusMessage ?? "Checking the Hub…")
            }
            .font(.callout)
            .foregroundStyle(ScenixPalette.secondaryText)
            .accessibilityElement(children: .combine)
        } else if let error = hub.errorMessage {
            Label(error, systemImage: "exclamationmark.triangle.fill")
                .font(.callout)
                .foregroundStyle(ScenixPalette.error)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityLabel("Connection error: \(error)")
        } else if let warning = hub.storageWarning {
            Label(warning, systemImage: "key.horizontal.fill")
                .font(.callout)
                .foregroundStyle(ScenixPalette.warning)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var limits: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Preview limits", systemImage: "info.circle")
                .font(.headline)
                .foregroundStyle(.white)

            Divider().overlay(ScenixPalette.border)

            LimitRow(
                title: "Hub mode only",
                detail: "This app connects to an existing Hub. It does not install or run a Hub on this Mac."
            )
            LimitRow(
                title: "No standalone engine",
                detail: "There is no on-device P2P engine, local cache, or standalone media library in this preview."
            )
            LimitRow(
                title: "Web playback path",
                detail: "The Hub's browser-safe playback and selected theme are used as-is. This preview is not a native media decoder."
            )

            Divider().overlay(ScenixPalette.border)

            Text("For a remote Hub, use HTTPS and the Hub's access token. Do not expose an unauthenticated Hub to the public internet.")
                .font(.caption)
                .foregroundStyle(ScenixPalette.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(28)
        .frame(maxWidth: 390, alignment: .leading)
        .background(ScenixPalette.panel.opacity(0.74), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(ScenixPalette.border, lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
    }
}

private struct LimitRow: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
            Text(detail)
                .font(.callout)
                .foregroundStyle(ScenixPalette.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct HubSurfaceView: View {
    @ObservedObject var hub: HubSession
    let endpoint: URL
    @State private var webIsLoading = true
    @State private var webError: String?

    var body: some View {
        VStack(spacing: 0) {
            header

            if let warning = hub.storageWarning {
                Label(warning, systemImage: "key.horizontal.fill")
                    .font(.callout)
                    .foregroundStyle(ScenixPalette.warning)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(ScenixPalette.panel)
                    .accessibilityLabel("Secure storage warning: \(warning)")
            }

            ZStack(alignment: .topTrailing) {
                HubWebView(
                    endpoint: endpoint,
                    token: hub.activeToken,
                    reloadID: hub.webReloadID,
                    isLoading: $webIsLoading,
                    errorMessage: $webError
                )
                .id(hub.webReloadID)
                .accessibilityLabel("Scenix Hub web interface")

                if webIsLoading {
                    ProgressView("Loading Hub…")
                        .padding(12)
                        .background(.regularMaterial, in: Capsule())
                        .padding(16)
                        .accessibilityElement(children: .combine)
                }
            }

            if let webError {
                Label(webError, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(ScenixPalette.error)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(ScenixPalette.panel)
                    .accessibilityLabel("Web surface error: \(webError)")
            }

            footer
        }
        .background(Color.black)
    }

    private var header: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Scenix")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text(hub.health?.summary ?? endpoint.absoluteString)
                    .font(.caption)
                    .foregroundStyle(ScenixPalette.secondaryText)
                    .lineLimit(1)
            }

            Spacer(minLength: 24)

            if webIsLoading {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel("Loading Hub")
            }

            Button {
                hub.reloadWebSurface()
            } label: {
                Label("Reload", systemImage: "arrow.clockwise")
            }
            .disabled(webIsLoading)
            .accessibilityHint("Reloads the Hub's web interface without changing its theme or settings.")

            Button {
                hub.showOnboarding()
            } label: {
                Label("Change Hub", systemImage: "server.rack")
            }
            .accessibilityHint("Returns to Hub connection setup.")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(ScenixPalette.panel)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(ScenixPalette.border)
                .frame(height: 1)
        }
    }

    private var footer: some View {
        HStack(spacing: 8) {
            Image(systemName: "paintpalette")
                .accessibilityHidden(true)
            Text("The Hub controls this screen's theme and catalog.")
            Spacer()
            Text("Hub mode · web playback path")
        }
        .font(.caption)
        .foregroundStyle(ScenixPalette.secondaryText)
        .padding(.horizontal, 20)
        .padding(.vertical, 9)
        .background(ScenixPalette.panel)
    }
}

enum ScenixPalette {
    static let background = Color(red: 0.035, green: 0.043, blue: 0.075)
    static let panel = Color(red: 0.070, green: 0.082, blue: 0.130)
    static let border = Color.white.opacity(0.14)
    static let secondaryText = Color(red: 0.73, green: 0.77, blue: 0.86)
    static let red = Color(red: 0.90, green: 0.04, blue: 0.11)
    static let blueGlow = Color(red: 0.18, green: 0.45, blue: 0.92)
    static let error = Color(red: 1.0, green: 0.50, blue: 0.44)
    static let warning = Color(red: 1.0, green: 0.78, blue: 0.33)
}
