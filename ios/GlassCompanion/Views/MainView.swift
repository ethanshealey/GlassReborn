import SwiftUI

struct MainView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        TabView {
            ConnectionView()
                .tabItem { Label("Glass", systemImage: "glasses") }

            NotificationsView()
                .tabItem { Label("Notify", systemImage: "bell") }

            MediaGalleryView()
                .tabItem { Label("Gallery", systemImage: "photo") }

            AIView()
                .tabItem { Label("AI", systemImage: "brain") }

            SettingsView()
                .tabItem { Label("Settings", systemImage: "gear") }
        }
        .accentColor(.cyan)
    }
}
