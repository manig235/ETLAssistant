import SwiftUI
import UIKit

/// 첨부 메뉴 "사진 찍기"(Android `TakePicture` → iOS `UIImagePickerController(.camera)`). 찍은 사진은 사진 첨부와 같은 경로로
/// 작업 폴더에 저장해 보낼 첨부 줄에 붙인다(자동 전송 안 함). 카메라가 없는 기기(시뮬레이터)에서는 메뉴에 보이지 않는다.
struct ChatCameraPicker: UIViewControllerRepresentable {
    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    let onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = ["public.image"]
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: ChatCameraPicker
        init(_ parent: ChatCameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onImage(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

extension ChatController {
    /// 찍은 사진 한 장을 보낼 첨부로(긴 변 2048px JPEG로 다시 저장).
    func importCameraImage(_ image: UIImage) {
        guard remainingSlots > 0 else {
            ToastCenter.shared.show("한 번에 5개까지 첨부할 수 있어요")
            return
        }
        guard let data = image.jpegData(compressionQuality: 0.95),
              let a = Self.saveUpload(data: data, name: "shot-\(Self.nowMs()).jpg", mime: "image/jpeg") else {
            ToastCenter.shared.show("첨부하지 못했어요")
            return
        }
        pending.append(a)
    }
}
