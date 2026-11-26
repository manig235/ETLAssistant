import ChatKit
import ETLKit
import SwiftUI
import UIKit

/// 첨부 묶음(Kotlin `AttachmentGroup`). 비서 쪽은 보이는 첨부와 "참고 자료"(referenceOnly·쪽 미리보기)를 나눠 참고 자료는 접어 둔다.
struct ChatAttachmentGroup: View {
    let attachments: [Attachment]
    let isUser: Bool
    let maxWidth: CGFloat
    let onOpen: (Attachment) -> Void
    let onRedownload: (String) -> Void
    @State private var referencesOpen = false

    var body: some View {
        let visible = isUser ? attachments : attachments.filter { !$0.referenceOnly && !$0.pagePreview }
        let references = isUser ? [] : attachments.filter { $0.referenceOnly || $0.pagePreview }
        VStack(alignment: isUser ? .trailing : .leading, spacing: 6) {
            AttachmentList(list: visible, isUser: isUser, maxWidth: maxWidth, onOpen: onOpen, onRedownload: onRedownload)
            if !references.isEmpty {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { referencesOpen.toggle() }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: referencesOpen ? "chevron.up" : "chevron.down")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(YMColor.labelTertiary)
                            .frame(width: 18, height: 18)
                        Text("참고 자료 \(references.count)개")
                            .font(YMFont.caption1Regular)
                            .foregroundStyle(YMColor.labelTertiary)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .contentShape(RoundedRectangle(cornerRadius: 8))
                }
                .buttonStyle(YMRowPressStyle(cornerRadius: 8))
                .accessibilityLabel(referencesOpen ? "참고 자료 접기" : "참고 자료 펼치기")
                .accessibilityValue(referencesOpen ? "펼쳐짐" : "접힘")
                if referencesOpen {
                    AttachmentList(list: references, isUser: false, maxWidth: maxWidth, onOpen: onOpen, onRedownload: onRedownload)
                }
            }
        }
    }
}

/// 사진 격자 → 쪽 미리보기 줄 → 파일 카드 순서.
private struct AttachmentList: View {
    let list: [Attachment]
    let isUser: Bool
    let maxWidth: CGFloat
    let onOpen: (Attachment) -> Void
    let onRedownload: (String) -> Void

    var body: some View {
        let images = list.filter { $0.isImage && !$0.pagePreview }
        let pages = list.filter { $0.pagePreview }
        let files = list.filter { !$0.isImage && !$0.pagePreview }
        VStack(alignment: isUser ? .trailing : .leading, spacing: 6) {
            if !images.isEmpty {
                let side: CGFloat = images.count == 1 ? 200 : 120
                YMFlowLayout(spacing: 6, lineSpacing: 6, alignment: isUser ? .trailing : .leading) {
                    ForEach(images, id: \.self) { a in
                        ChatImageThumb(attachment: a, width: side, height: side, fit: false)
                            .onTapGesture { onOpen(a) }
                    }
                }
                .frame(maxWidth: maxWidth, alignment: isUser ? .trailing : .leading)
            }
            if !pages.isEmpty {
                Text("비서가 본 쪽 \(pages.count)장")
                    .font(YMFont.caption1Regular)
                    .foregroundStyle(YMColor.labelTertiary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(pages, id: \.self) { a in
                            ChatImageThumb(attachment: a, width: 110, height: 155, fit: true)
                                .onTapGesture { onOpen(a) }
                        }
                    }
                }
                .frame(maxWidth: maxWidth)
            }
            ForEach(files, id: \.self) { a in
                ChatFileCard(attachment: a, maxWidth: maxWidth, onOpen: onOpen, onRedownload: onRedownload)
            }
        }
    }
}

/// 사진 썸네일: 모서리 14, surfaceNeutralSecondary, crop(쪽 미리보기는 fit). 썸네일이 없으면 사진/깨진 사진 아이콘.
/// 탭 = 열기, 길게 = 공유.
struct ChatImageThumb: View {
    let attachment: Attachment
    let width: CGFloat
    let height: CGFloat
    let fit: Bool
    @State private var image: UIImage?
    @State private var loaded = false

    var body: some View {
        let url = attachment.path.flatMap(ContainerPath.resolve).map { URL(fileURLWithPath: $0) }
        let exists = url != nil
        ZStack {
            RoundedRectangle(cornerRadius: 14).fill(YMColor.surfaceSecondary)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: fit ? .fit : .fill)
                    .frame(width: width, height: height)
                    .clipped()
            } else if loaded {
                Image(systemName: exists ? "photo" : "photo.badge.exclamationmark")
                    .font(.system(size: 26))
                    .foregroundStyle(YMColor.labelTertiary)
            }
        }
        .frame(width: width, height: height)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .contextMenu {
            if let url, exists { ShareLink(item: url) { Label("공유", systemImage: "square.and.arrow.up") } }
        }
        .accessibilityLabel(attachment.name)
        .accessibilityAddTraits(.isButton)
        .task(id: attachment.path) {
            image = await ThumbnailLoader.load(attachment.path, maxPixel: max(width, height) * 3)
            loaded = true
        }
    }
}

enum ThumbnailLoader {
    private static let cache = NSCache<NSString, UIImage>()

    static func load(_ path: String?, maxPixel: CGFloat) async -> UIImage? {
        guard let path else { return nil }
        let key = "\(path)#\(Int(maxPixel))" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        let img: UIImage? = await Task.detached(priority: .userInitiated) {
            guard let image = UIImage(contentsOfFile: path) else { return nil }
            let longest = max(image.size.width, image.size.height) * image.scale
            if longest <= maxPixel { return image }
            let s = maxPixel / longest
            return image.preparingThumbnail(of: CGSize(width: image.size.width * image.scale * s,
                                                       height: image.size.height * image.scale * s)) ?? image
        }.value
        if let img { cache.setObject(img, forKey: key) }
        return img
    }
}

/// 파일 카드(Kotlin `FileCard`): [아이콘 타일] 파일명 / 상세 + (있으면) 공유 버튼. 없어진 파일은 "다시 받기".
struct ChatFileCard: View {
    let attachment: Attachment
    let maxWidth: CGFloat
    let onOpen: (Attachment) -> Void
    let onRedownload: (String) -> Void

    /// Kotlin `GeneratedFilesLogic.SUBDIR`.
    static let generatedSubdir = "영민이가 만든 파일"

    var body: some View {
        let a = attachment
        let workspacePath = a.path.flatMap(ContainerPath.resolve)
        let workspaceExists = workspacePath != nil
        let phoneURL = ContainerPath.resolveFileURL(a.uri) ?? a.uri.flatMap(URL.init(string:))
        let phoneAlive = ContainerPath.resolveFileURL(a.uri) != nil
        // 자동 정리로 지운 사본이면 "자동 정리된 파일이에요 · 다시 받기"(Kotlin `DownloadCleanup.wasCleaned`).
        let cleaned = !workspaceExists && !phoneAlive && FilesCleanupService.cleanup.wasCleaned(a.uri)
        let state = AttachmentLogic.availability(workspaceExists: workspaceExists, phoneUri: a.uri, phoneAlive: phoneAlive, cleaned: cleaned)
        let exists = state == .ok
        let generated = a.shownPath?.contains(Self.generatedSubdir) ?? false
        let shareURL = phoneAlive ? phoneURL : workspacePath.map { URL(fileURLWithPath: $0) }

        HStack(spacing: 4) {
            Button { if exists { onOpen(a) } } label: {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 12) {
                        Image(systemName: Self.icon(AttachmentLogic.kindOf(a.name, a.mime)))
                            .font(.system(size: 20))
                            .foregroundStyle(YMColor.brandPrimary)
                            .frame(width: 40, height: 40)
                            .background(RoundedRectangle(cornerRadius: 10).fill(YMColor.brandSubtle))
                        VStack(alignment: .leading, spacing: 0) {
                            Text(a.name)
                                .font(YMFont.body2Medium)
                                .foregroundStyle(YMColor.label)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                            Text(detail(state: state, exists: exists, generated: generated))
                                .font(YMFont.caption1Regular)
                                .foregroundStyle(YMColor.labelSecondary)
                                .lineLimit(1)
                        }
                    }
                    if !exists && a.shownPath != nil {
                        Button { onRedownload(AttachmentLogic.redownloadPrompt(a.name, generated: generated)) } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.clockwise").font(.system(size: 13, weight: .semibold))
                                Text(generated ? "다시 만들기" : "다시 받기").font(YMFont.caption1Bold)
                            }
                            .foregroundStyle(YMColor.brandPrimary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 6)
                            .contentShape(RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(YMRowPressStyle(cornerRadius: 10))
                        .padding(.top, 6)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 16).fill(YMColor.surfaceSecondary))
                .contentShape(RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(YMPressStyle())
            .accessibilityHint(exists ? "열기" : "")
            if exists, let shareURL {
                ShareLink(item: shareURL) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 20))
                        .foregroundStyle(YMColor.brandPrimary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("공유")
            }
        }
        .frame(maxWidth: maxWidth, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func detail(state: AttachmentLogic.Availability, exists: Bool, generated: Bool) -> String {
        var parts = [Self.typeLabel(attachment)]
        if attachment.size > 0 { parts.append(AttachmentLogic.formatSize(attachment.size)) }
        if state == .cleaned {
            parts.append("자동 정리된 파일이에요")
        } else if !exists {
            parts.append(attachment.shownPath != nil ? "폰에서 지워진 파일이에요" : "정리된 파일이에요")
        } else if attachment.shownPath != nil {
            // Android "내 폰 Download/etl" → iOS 파일 앱의 이 앱 폴더(Documents/etl).
            parts.append(generated ? "파일 앱 영민이/\(EtlDownloads.userFolderName)/\(Self.generatedSubdir)" : "파일 앱 영민이/\(EtlDownloads.userFolderName)")
        }
        return parts.joined(separator: " · ")
    }

    /// 확장자 대문자(5자 이하, 아니면 "파일").
    static func typeLabel(_ a: Attachment) -> String {
        let ext = (a.name as NSString).pathExtension.uppercased()
        return !ext.isEmpty && ext.count <= 5 ? ext : "파일"
    }

    static func icon(_ kind: AttachmentLogic.FileKind) -> String {
        switch kind {
        case .pdf: return "doc.richtext"
        case .image: return "photo"
        case .slides: return "play.rectangle"
        case .sheet: return "tablecells"
        case .doc: return "doc.text"
        case .hwp: return "doc.plaintext"
        case .archive: return "doc.zipper"
        case .code: return "chevron.left.forwardslash.chevron.right"
        case .other: return "doc"
        }
    }
}

/// 보낼 첨부 줄(Kotlin `PendingAttachments`): 가로 스크롤, 사진 64×64 / 파일 칩, 오른쪽 위 빼기 버튼.
struct ChatPendingAttachments: View {
    let controller: ChatController
    let onOpen: (Attachment) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(controller.pending, id: \.self) { a in
                    ZStack(alignment: .topTrailing) {
                        if a.isImage {
                            ChatImageThumb(attachment: a, width: 64, height: 64, fit: false)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .onTapGesture { onOpen(a) }
                        } else {
                            HStack(spacing: 8) {
                                Image(systemName: ChatFileCard.icon(AttachmentLogic.kindOf(a.name, a.mime)))
                                    .font(.system(size: 17))
                                    .foregroundStyle(YMColor.brandPrimary)
                                    .frame(width: 20, height: 20)
                                Text(a.name)
                                    .font(YMFont.caption1Medium)
                                    .foregroundStyle(YMColor.label)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            .padding(.leading, 10)
                            .padding(.trailing, 28)
                            .padding(.vertical, 10)
                            .frame(maxWidth: 200, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 12).fill(YMColor.surfaceSecondary))
                            .onTapGesture { onOpen(a) }
                        }
                        Button { controller.removePending(a) } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundStyle(YMColor.foregroundInverse)
                                .frame(width: 20, height: 20)
                                .background(Circle().fill(YMColor.surfaceInverse))
                        }
                        .buttonStyle(YMPressStyle())
                        .padding(3)
                        .accessibilityLabel("\(a.name) 빼기")
                    }
                }
                if controller.importing > 0 {
                    Text("가져오는 중…")
                        .font(YMFont.caption1Regular)
                        .foregroundStyle(YMColor.labelSecondary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
    }
}
