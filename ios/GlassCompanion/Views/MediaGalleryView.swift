import SwiftUI

struct MediaGalleryView: View {
    @EnvironmentObject var app: AppState
    @State private var selectedPhoto: WiFiMediaClient.PhotoItem?
    private let columns = [GridItem(.adaptive(minimum: 90), spacing: 2)]

    var body: some View {
        NavigationView {
            Group {
                if app.wifi.photos.isEmpty {
                    VStack(spacing: 16) {
                        Image(systemName: "photo.on.rectangle")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text(app.wifi.connected
                             ? "No photos on Glass yet.\nTake a photo with the Glass camera."
                             : "Connect to Glass to browse photos.")
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 2) {
                            ForEach(app.wifi.photos) { photo in
                                PhotoThumbnail(photo: photo)
                                    .onTapGesture { selectedPhoto = photo }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Glass Gallery (\(app.wifi.photos.count))")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        app.wifi.requestList()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(!app.wifi.connected)
                }
            }
            .sheet(item: $selectedPhoto) { photo in
                PhotoDetailView(photo: photo) {
                    app.wifi.downloadPhoto(name: photo.name)
                }
            }
        }
    }
}

private struct PhotoThumbnail: View {
    let photo: WiFiMediaClient.PhotoItem

    var body: some View {
        ZStack {
            Color.black
            if let url = photo.localURL, let img = UIImage(contentsOfFile: url.path) {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "photo")
                    .foregroundColor(.secondary)
            }
        }
        .frame(width: 90, height: 90)
        .clipped()
    }
}

private struct PhotoDetailView: View {
    let photo: WiFiMediaClient.PhotoItem
    let onDownload: () -> Void
    @Environment(\.dismiss) var dismiss

    var body: some View {
        NavigationView {
            VStack {
                if let url = photo.localURL, let img = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: img)
                        .resizable()
                        .scaledToFit()
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "arrow.down.circle")
                            .font(.system(size: 56))
                            .foregroundColor(.cyan)
                        Text("Not downloaded yet")
                        Button("Download from Glass", action: onDownload)
                            .buttonStyle(.bordered)
                    }
                }
                Spacer()
                Text(photo.name)
                    .font(.caption)
                    .foregroundColor(.secondary)
                Text(ByteCountFormatter.string(fromByteCount: photo.size, countStyle: .file))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .padding()
            .navigationTitle("Photo")
            .navigationBarItems(trailing: Button("Done") { dismiss() })
        }
    }
}
