import ChatKit
import ETLKit
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// 첨부 가져오기(Kotlin `AttachmentImporter`): 고른 사진·파일을 작업 폴더 `etl-files/uploads`로 복사해 보낼 첨부 줄에 붙인다(자동 전송 안 함).
/// 사진은 긴 변 2048px JPEG(85%)로 다시 저장해 용량과 위치 정보를 줄인다. GIF·SVG는 그대로. 사진이 아닌 파일은 50MB까지.
extension ChatController {
    var remainingSlots: Int { max(0, AttachmentLogic.MAX_PENDING - pending.count - importing) }

    func importPhotos(_ items: [PhotosPickerItem]) async {
        guard !items.isEmpty else { return }
        let take = Array(items.prefix(remainingSlots))
        if take.count < items.count { ToastCenter.shared.show("한 번에 5개까지 첨부할 수 있어요") }
        importing += take.count
        for item in take {
            defer { importing -= 1 }
            guard let data = try? await item.loadTransferable(type: Data.self) else {
                ToastCenter.shared.show("파일을 가져오지 못했어요")
                continue
            }
            let type = item.supportedContentTypes.first(where: { $0.conforms(to: .image) }) ?? .jpeg
            let mime = type.preferredMIMEType ?? "image/jpeg"
            let ext = type.preferredFilenameExtension ?? "jpg"
            let name = "photo-\(Self.nowMs() % 1_000_000).\(ext)"
            if let a = Self.saveUpload(data: data, name: name, mime: mime) { pending.append(a) } else {
                ToastCenter.shared.show("첨부하지 못했어요")
            }
        }
    }

    func importFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else {
            ToastCenter.shared.show("파일을 열 수 없어요")
            return
        }
        let take = Array(urls.prefix(remainingSlots))
        if take.count < urls.count { ToastCenter.shared.show("한 번에 5개까지 첨부할 수 있어요") }
        for url in take {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            let type = UTType(filenameExtension: url.pathExtension)
            let mime = type?.preferredMIMEType ?? "application/octet-stream"
            if !mime.hasPrefix("image/") && size > AttachmentLogic.MAX_FILE_BYTES {
                ToastCenter.shared.show("50MB보다 큰 파일은 첨부할 수 없어요")
                continue
            }
            guard let data = try? Data(contentsOf: url) else {
                ToastCenter.shared.show("파일을 열 수 없어요")
                continue
            }
            if let a = Self.saveUpload(data: data, name: url.lastPathComponent, mime: mime) { pending.append(a) } else {
                ToastCenter.shared.show("첨부하지 못했어요")
            }
        }
    }

    func removePending(_ a: Attachment) {
        pending.removeAll { $0 == a }
        if let p = a.path { try? FileManager.default.removeItem(atPath: p) }
    }

    /// 작업 폴더에 저장하고 `Attachment`를 만든다.
    static func saveUpload(data: Data, name: String, mime: String) -> Attachment? {
        let dir = AppPaths.directory(AttachmentLogic.UPLOAD_DIR)
        let safe = EtlLogic.safeName(name, "file")
        var bytes = data
        var outMime = mime
        var reencoded = false
        if AttachmentLogic.shouldReencode(mime), let image = UIImage(data: data),
           let jpeg = downscaled(image).jpegData(compressionQuality: CGFloat(AttachmentLogic.IMAGE_QUALITY) / 100) {
            bytes = jpeg
            outMime = "image/jpeg"
            reencoded = true
        }
        let file = dir.appendingPathComponent(AttachmentLogic.uploadName(nowMs(), safe, reencoded: reencoded))
        do { try bytes.write(to: file, options: .atomic) } catch { return nil }
        let shown = reencoded ? file.lastPathComponent.split(separator: "-", maxSplits: 1).last.map(String.init) ?? safe : safe
        return Attachment(shown, outMime, Int64(bytes.count), file.path)
    }

    private static func downscaled(_ image: UIImage) -> UIImage {
        let maxSide = CGFloat(AttachmentLogic.IMAGE_MAX_SIDE)
        let w = image.size.width * image.scale, h = image.size.height * image.scale
        let longest = max(w, h)
        let scale = longest > maxSide ? maxSide / longest : 1
        let size = CGSize(width: floor(w * scale), height: floor(h * scale))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
