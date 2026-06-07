import SwiftUI
import Photos

struct MediaGalleryView: View {
    @EnvironmentObject var app: AppState
    @State private var selectedPhotoName: String?
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
                                    .onTapGesture { selectedPhotoName = photo.name }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Glass Gallery (\(app.wifi.photos.count))")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if let p = app.wifi.downloadProgress {
                        HStack(spacing: 6) {
                            ProgressView()
                                .scaleEffect(0.7)
                            Text("\(p.done)/\(p.total)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    } else {
                        Button { app.wifi.requestList() } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                        .disabled(!app.wifi.connected)
                    }
                }
            }
            // Pass the wifi client so the detail view observes live updates.
            .sheet(isPresented: Binding(
                get: { selectedPhotoName != nil },
                set: { if !$0 { selectedPhotoName = nil } }
            )) {
                if let name = selectedPhotoName {
                    PhotoDetailView(photoName: name, wifi: app.wifi)
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
    let photoName: String
    @ObservedObject var wifi: WiFiMediaClient
    @Environment(\.dismiss) var dismiss
    @State private var saveState: SaveState = .idle

    private var photo: WiFiMediaClient.PhotoItem? {
        wifi.photos.first { $0.name == photoName }
    }

    enum SaveState { case idle, saving, saved, failed }

    var body: some View {
        NavigationView {
            VStack(spacing: 16) {
                if let url = photo?.localURL, let img = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: img)
                        .resizable()
                        .scaledToFit()

                    saveButton(img)
                } else {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("Downloading…")
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                Spacer()
                if let p = photo {
                    Text(p.name)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(ByteCountFormatter.string(fromByteCount: p.size, countStyle: .file))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
            .padding()
            .navigationTitle("Photo")
            .navigationBarItems(trailing: Button("Done") { dismiss() })
        }
    }

    @ViewBuilder
    private func saveButton(_ image: UIImage) -> some View {
        Button {
            saveToPhotos(image)
        } label: {
            switch saveState {
            case .idle:
                Label("Save to Photos", systemImage: "square.and.arrow.down")
            case .saving:
                Label("Saving…", systemImage: "square.and.arrow.down")
                    .foregroundColor(.secondary)
            case .saved:
                Label("Saved", systemImage: "checkmark")
                    .foregroundColor(.green)
            case .failed:
                Label("Failed — check Photos permission", systemImage: "xmark")
                    .foregroundColor(.red)
            }
        }
        .buttonStyle(.bordered)
        .disabled(saveState == .saving || saveState == .saved)
    }

    private func saveToPhotos(_ image: UIImage) {
        saveState = .saving
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { saveState = .failed }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            }) { success, _ in
                DispatchQueue.main.async {
                    saveState = success ? .saved : .failed
                }
            }
        }
    }
}
