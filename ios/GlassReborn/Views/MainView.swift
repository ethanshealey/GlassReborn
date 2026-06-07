import SwiftUI

struct MainView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        TabView {
            ConnectionView()
                .tabItem { Label("Glass", image: "GlassLogo") }

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
        .onOpenURL { url in
            app.handleURL(url)
        }
    }
}
