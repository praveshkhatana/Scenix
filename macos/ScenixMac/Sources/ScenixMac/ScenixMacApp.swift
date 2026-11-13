import SwiftUI

@main
struct ScenixMacApp: App {
    @StateObject private var hub = HubSession()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(hub)
                .frame(minWidth: 960, minHeight: 640)
        }
        .commands {
            CommandMenu("Hub") {
                Button("Reconnect") {
                    Task { await hub.reconnect() }
                }
                .disabled(hub.draftEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || hub.isBusy)

                Button("Reload web surface") {
                    hub.reloadWebSurface()
                }
                .disabled(!hub.isConnected)
                .keyboardShortcut("r", modifiers: .command)

                Divider()

                Button("Change Hub…") {
                    hub.showOnboarding()
                }
                .disabled(hub.isBusy)

                Button("Forget this Mac") {
                    hub.forgetSavedHub()
                }
                .disabled(!hub.hasSavedHub)
            }
        }
    }
}

private struct RootView: View {
    @EnvironmentObject private var hub: HubSession

    var body: some View {
        Group {
            if hub.isConnected, let endpoint = hub.activeEndpoint {
                HubSurfaceView(hub: hub, endpoint: endpoint)
            } else {
                PairingView(hub: hub)
            }
        }
        .task {
            await hub.restoreAndValidateIfAvailable()
        }
    }
}
