import ChatKit
import ETLKit
import UIKit
import UniformTypeIdentifiers

/// "공유 → 영민이"(Kotlin `chat/ShareReceiverActivity.kt`). 받은 파일을 App Group 폴더로 복사하고(최대 5개, 사진이 아닌 파일은 50MB까지)
/// 글은 입력창에 넣을 수 있게 남긴 뒤 영민이를 연다. 앱이 새 대화의 입력창·첨부로 옮긴다. 자동으로 보내지 않는다.
/// 받은 내용은 입력창·첨부로만 쓰고 그 밖의 동작은 하지 않는다.
final class ShareViewController: UIViewController {
    private let label = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let closeButton = UIButton(type: .system)
    private var started = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.25)
        let card = UIView()
        card.backgroundColor = .systemBackground
        card.layer.cornerRadius = 16
        card.translatesAutoresizingMaskIntoConstraints = false
        label.text = "가져오는 중이에요…"
        label.font = .preferredFont(forTextStyle: .body)
        label.numberOfLines = 0
        label.textAlignment = .center
        closeButton.setTitle("닫기", for: .normal)
        closeButton.isHidden = true
        closeButton.addAction(UIAction { [weak self] _ in self?.finish() }, for: .touchUpInside)
        spinner.startAnimating()
        let stack = UIStackView(arrangedSubviews: [spinner, label, closeButton])
        stack.axis = .vertical
        stack.spacing = 12
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        view.addSubview(card)
        // iPad의 넓은 공유 창에서도 카드가 지나치게 넓어지지 않게 최대 420pt로 둔다(아이폰은 폭의 78% 그대로).
        let preferredWidth = card.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.78)
        preferredWidth.priority = .defaultHigh
        NSLayoutConstraint.activate([
            card.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            card.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            preferredWidth,
            card.widthAnchor.constraint(lessThanOrEqualToConstant: 420),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -16),
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        AppGroup.logContainer("share")
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        Task { @MainActor in
            let result = await ShareImport.run(items)
            switch result {
            case .saved(let id):
                if openApp(WidgetLinks.url(.share(id))) {
                    finish()
                } else {
                    show("영민이를 열면 공유한 내용이 새 대화에 들어가요")
                }
            case .nothing:
                show("가져올 수 있는 내용이 없어요")
            case .failed:
                show("공유한 내용을 영민이에 넘기지 못했어요")
            }
        }
    }

    private func show(_ text: String) {
        spinner.stopAnimating()
        spinner.isHidden = true
        label.text = text
        closeButton.isHidden = false
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }

    /// 공유 확장은 `UIApplication.shared`를 쓸 수 없어서, 응답자 사슬의 UIApplication에 `openURL:options:completionHandler:`를 보낸다.
    /// 열지 못하면 false(앱을 직접 열면 같은 내용을 가져간다).
    private func openApp(_ url: URL) -> Bool {
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication, app.responds(to: selector) {
                typealias Open = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, AnyObject?) -> Void
                let imp = app.method(for: selector)
                unsafeBitCast(imp, to: Open.self)(app, selector, url as NSURL, NSDictionary(), nil)
                return true
            }
            responder = r.next
        }
        return false
    }
}

/// 받은 항목을 App Group의 `share/{id}/`로 옮긴다.
enum ShareImport {
    enum Result { case saved(String), nothing, failed }

    @MainActor
    static func run(_ items: [NSExtensionItem]) async -> Result {
        guard AppGroup.container != nil else { return .failed }
        let id = UUID().uuidString
        guard let dir = PendingShare.folder(id) else { return .failed }
        do { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) } catch { return .failed }

        let subject = items.lazy.compactMap { $0.attributedTitle?.string }.first { !$0.isEmpty }
        var texts: [String] = items.compactMap { $0.attributedContentText?.string }.filter { !$0.isEmpty }
        var fileProviders: [NSItemProvider] = []
        for provider in items.flatMap({ $0.attachments ?? [] }) {
            if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) && !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                if let url = await loadURL(provider) {
                    if url.isFileURL { fileProviders.append(provider) } else if !texts.contains(where: { $0.contains(url.absoluteString) }) { texts.append(url.absoluteString) }
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) && provider.suggestedName == nil
                        && !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                if let t = await loadText(provider), !texts.contains(t) { texts.append(t) }
            } else {
                fileProviders.append(provider)
            }
        }
        let take = Array(fileProviders.prefix(AttachmentLogic.MAX_PENDING))
        var files: [PendingShare.File] = []
        var failures: [String] = []
        for (i, provider) in take.enumerated() {
            switch await copyFile(provider, index: i, into: dir) {
            case .success(let f): files.append(f)
            case .failure(let message): failures.append(message.text)
            }
        }
        let text = ShareIntentLogic.combinedText(text: texts.joined(separator: "\n"), subject: subject, hasFiles: !take.isEmpty)
        if files.isEmpty && text == nil && failures.isEmpty {
            PendingShare.remove(id)
            return .nothing
        }
        let share = PendingShare(id: id, createdAt: Date(), text: text, files: files, failures: failures, dropped: fileProviders.count - take.count)
        do { try share.write() } catch {
            PendingShare.remove(id)
            return .failed
        }
        return .saved(id)
    }

    struct Failure: Error { let text: String }

    private static func loadURL(_ p: NSItemProvider) async -> URL? {
        await withCheckedContinuation { c in
            _ = p.loadObject(ofClass: URL.self) { url, _ in c.resume(returning: url) }
        }
    }

    private static func loadText(_ p: NSItemProvider) async -> String? {
        await withCheckedContinuation { c in
            p.loadItem(forTypeIdentifier: UTType.plainText.identifier) { item, _ in
                if let s = item as? String { c.resume(returning: s) } else if let d = item as? Data { c.resume(returning: String(data: d, encoding: .utf8)) } else if let a = item as? NSAttributedString { c.resume(returning: a.string) } else { c.resume(returning: nil) }
            }
        }
    }

    /// 파일 표현을 받아 바로 복사한다(받은 임시 파일은 콜백 안에서만 읽을 수 있다).
    private static func copyFile(_ p: NSItemProvider, index: Int, into dir: URL) async -> Swift.Result<PendingShare.File, Failure> {
        let types = p.registeredTypeIdentifiers.compactMap(UTType.init)
        guard let type = types.first(where: { $0.conforms(to: .data) || $0.conforms(to: .package) }) ?? types.first else {
            return .failure(Failure(text: "첨부하지 못했어요"))
        }
        return await withCheckedContinuation { c in
            _ = p.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { c.resume(returning: .failure(Failure(text: "파일을 열 수 없어요"))); return }
                let fileType = UTType(filenameExtension: url.pathExtension) ?? type
                let mime = fileType.preferredMIMEType ?? type.preferredMIMEType ?? "application/octet-stream"
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
                if !mime.hasPrefix("image/") && size > AttachmentLogic.MAX_FILE_BYTES {
                    c.resume(returning: .failure(Failure(text: "50MB보다 큰 파일은 첨부할 수 없어요")))
                    return
                }
                var name = p.suggestedName.map { EtlLogic.safeName($0, "file") } ?? EtlLogic.safeName(url.lastPathComponent, "file")
                if (name as NSString).pathExtension.isEmpty, let ext = fileType.preferredFilenameExtension ?? type.preferredFilenameExtension {
                    name += ".\(ext)"
                }
                let stored = "\(index)-\(name)"
                do {
                    try FileManager.default.copyItem(at: url, to: dir.appendingPathComponent(stored))
                    c.resume(returning: .success(PendingShare.File(name: name, mime: mime, stored: stored)))
                } catch {
                    c.resume(returning: .failure(Failure(text: "첨부하지 못했어요")))
                }
            }
        }
    }
}
