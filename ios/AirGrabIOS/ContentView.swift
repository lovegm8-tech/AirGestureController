import SwiftUI
import UniformTypeIdentifiers

/// iOS does not have Android's all-app front-camera gesture overlay.
/// It uses explicit Share Sheet / Files input and a foreground Core NFC tap.
struct ContentView: View {
    @StateObject private var model = TransferModel()
    @State private var showImporter = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("先讓兩支手機連上同一個 Wi-Fi 或手機熱點，再在 Android「懸浮操作」開啟 iPhone NFC 傳送／接收頁面。iPhone 需保持此畫面在前景。")
                        .font(.subheadline)
                } header: {
                    Label("連線方式", systemImage: "wifi")
                }

                Section("傳送至 Android") {
                    Toggle("傳送網頁網址", isOn: $model.sendingURL)
                    if model.sendingURL {
                        TextField("https://example.com", text: $model.typedLink)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                    } else {
                        Button("從「檔案」選擇 1～32 個原始檔") { showImporter = true }
                        Button("更新照片／Safari／其他 App 的分享內容") {
                            model.reloadShared()
                        }
                        ForEach(model.outgoing) { file in
                            Label(file.displayName, systemImage: "doc")
                                .font(.subheadline)
                                .lineLimit(1)
                        }
                        if !model.outgoing.isEmpty {
                            Button("清除清單", role: .destructive) { model.clearOutgoing() }
                        }
                        Text("相簿或其他 App 內請使用系統「分享 → 懸浮操作」，回到這裡再按傳送。來源 App 只給網址時，僅傳網址。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Button {
                        model.startNFC(send: true)
                    } label: {
                        Label("NFC 碰一碰傳送給 Android", systemImage: "iphone.gen3.radiowaves.left.and.right")
                    }
                    .disabled(model.busy)
                }

                Section("從 Android 接收") {
                    Text("Android 選擇照片、影片、檔案或網址，按「分享 → 懸浮操作 · iPhone NFC / Wi-Fi」並待命。")
                        .font(.subheadline)
                    Button {
                        model.startNFC(send: false)
                    } label: {
                        Label("NFC 碰一碰接收", systemImage: "arrow.down.circle")
                    }
                    .disabled(model.busy)
                }

                if model.awaitingTrust {
                    Section("NFC 裝置身分確認") {
                        Text(model.recognizedIdentity
                             ? "已信任的 Android 裝置"
                             : "偵測到新的 Android 裝置金鑰，請確認來源手機無誤；陌生裝置不可直接信任。")
                            .font(.subheadline)
                        Text(model.pendingFingerprint)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                        Button("信任這台 Android 並繼續傳輸") {
                            model.approveIdentity()
                        }
                        Button("取消配對", role: .destructive) {
                            model.denyIdentity()
                        }
                    }
                }

                if let link = model.receivedLink {
                    Section("收到的網頁網址") {
                        Text(link.absoluteString)
                            .font(.caption).textSelection(.enabled)
                        Link(destination: link) {
                            Label("自行開啟網址", systemImage: "safari")
                        }
                    }
                }

                Section("已驗證的接收檔案") {
                    ForEach(model.received, id: \.path) { file in
                        VStack(alignment: .leading, spacing: 5) {
                            ShareLink(item: file) {
                                Label(file.lastPathComponent, systemImage: "square.and.arrow.up")
                                    .lineLimit(1)
                            }
                            if ["heic", "heif", "jpg", "jpeg", "png", "gif", "tiff",
                                "mp4", "mov", "m4v"].contains(file.pathExtension.lowercased()) {
                                Button("另存至照片 App") { model.saveToPhotos(file) }
                                    .font(.caption)
                            }
                        }
                    }
                }

                Section("傳輸狀態") {
                    Text(model.status).font(.subheadline)
                    if model.busy { ProgressView(value: model.progress) }
                    Text("原始檔逐檔 SHA-256 驗證，AES-256-GCM 加密。單批最多 32 個檔案。無中斷續傳；需同一區域網路或手機熱點。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("懸浮操作")
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [.item],
                          allowsMultipleSelection: true) { result in
                switch result {
                case .success(let files): model.addFiles(files)
                case .failure(let error): model.status = error.localizedDescription
                }
            }
            .onAppear {
                model.reloadShared()
                model.reloadReceived()
            }
        }
    }
}
