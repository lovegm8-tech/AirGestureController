import SwiftUI
import UniformTypeIdentifiers
import AVFoundation

struct ContentView: View {
    @StateObject private var model = TransferModel()
    @State private var pairingText = ""
    @State private var showScanner = false
    @State private var importFile = false

    var body: some View {
        NavigationStack {
            Form {
                Section("與 S26 Ultra 配對") {
                    if let pairing = model.pairing {
                        Text("Android 位址：" + pairing.host)
                            .font(.subheadline)
                    } else {
                        Text("先在 S26 Ultra 開啟「隔空抓取：配對 iPhone 14」")
                            .font(.subheadline)
                    }
                    Button("掃描 Android 的配對 QR Code") { showScanner = true }
                    TextField("或貼上 airgrab://pair 連結", text: $pairingText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("使用配對連結") {
                        model.pair(link: pairingText)
                        pairingText = ""
                    }.disabled(pairingText.isEmpty)
                }
                Section("從 Android 接收") {
                    Button("搜尋待接收的照片、檔案與網址") { model.refreshOffers() }
                        .disabled(model.busy || model.pairing == nil)
                    ForEach(model.offers) { offer in
                        Button {
                            model.download(offer)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(offer.name)
                                Text(ByteCountFormatter.string(fromByteCount: offer.size, countStyle: .file))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }.disabled(model.busy)
                    }
                }
                Section("發送到 Android") {
                    Button("從「檔案」挑選原始檔") { importFile = true }
                        .disabled(model.busy || model.pairing == nil)
                    Button("更新其他 App 分享的待送項目") { model.reloadLocal() }
                    ForEach(model.inbox, id: \.path) { file in
                        Button("傳送：" + file.lastPathComponent) {
                            model.upload(file, fromShare: true)
                        }.disabled(model.busy || model.pairing == nil)
                    }
                    Text("iPhone 相簿、Chrome、YouTube、Facebook：使用分享選單選「隔空抓取」，再回到此 App 按傳送。來源若只提供連結，傳送的就是連結。")
                        .font(.caption)
                }
                Section("已接收的檔案") {
                    ForEach(model.received, id: \.path) { file in
                        ShareLink(item: file) {
                            Label(file.lastPathComponent, systemImage: "square.and.arrow.up")
                        }
                        if let url = model.receivedWebLink(from: file) {
                            Link("開啟收到的網址", destination: url)
                        }
                    }
                }
                Section("狀態") {
                    Text(model.status).font(.footnote)
                    if model.busy { ProgressView() }
                }
                Section {
                    Text("區域網路直接傳輸，不重新壓縮；接收後會驗證 SHA-256。必須保持此 App 前景與同一 Wi-Fi／熱點。初版尚未提供中斷續傳。")
                        .font(.caption)
                }
            }
            .navigationTitle("隔空抓取")
            .sheet(isPresented: $showScanner) {
                NavigationStack {
                    PairingScanner { code in
                        model.pair(link: code)
                        showScanner = false
                    }
                    .navigationTitle("掃描配對碼")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("關閉") { showScanner = false }
                        }
                    }
                }
            }
            .fileImporter(isPresented: $importFile, allowedContentTypes: [.item]) { result in
                if case .success(let file) = result { model.upload(file) }
                if case .failure(let error) = result { model.status = error.localizedDescription }
            }
            .onAppear { model.reloadLocal() }
        }
    }
}

struct PairingScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var didFind = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for: .video) { [weak self] allowed in
            guard allowed else { return }
            DispatchQueue.main.async { self?.configure() }
        }
    }

    private func configure() {
        guard let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !didFind, let result = objects.first as? AVMetadataMachineReadableCodeObject,
              let value = result.stringValue, value.hasPrefix("airgrab://pair?") else { return }
        didFind = true
        session.stopRunning()
        onCode?(value)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        DispatchQueue.global(qos: .utility).async { self.session.stopRunning() }
    }
}
