import SwiftUI
import UIKit

/// A programmatic edit applied outside the text view (toolbar insert,
/// mention pick, draft restore): the new text lands in the `text` binding
/// while this token drives the caret placement — and the optional
/// refocus — on the next representable update.
struct ComposerTextEdit: Equatable {
    let id = UUID()
    let selection: Int
    let refocus: Bool
}

/**
 * Cursor-tracking composer field (legacy Flutter `_MentionField` parity).
 * SwiftUI's `TextEditor` exposes no caret position, which reduced
 * @-mention detection to an end-of-text approximation; a UITextView-backed
 * field reports the real caret (UTF-16 offset, matching the shared
 * `ComposerRules` string indices) on every change so mentions, hashtag and
 * emoji inserts operate where the user is typing. The field grows with its
 * content (Flutter `maxLines: null`); the page scroll owns the scrolling.
 */
struct ComposerTextEditor: UIViewRepresentable {
    @Binding var text: String
    @Binding var cursor: Int
    @Binding var focused: Bool
    var pendingEdit: ComposerTextEdit?

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator
        view.backgroundColor = .clear
        view.font = .systemFont(ofSize: 16)
        view.textColor = UIColor(BitOSTheme.textPrimary)
        view.tintColor = UIColor(BitOSTheme.accent)
        view.keyboardAppearance = .dark
        view.isScrollEnabled = false
        view.textContainerInset = UIEdgeInsets(top: 8, left: 0, bottom: 8, right: 0)
        view.textContainer.lineFragmentPadding = 4
        view.text = text
        return view
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        context.coordinator.parent = self
        if uiView.text != text {
            uiView.text = text
        }
        if let edit = pendingEdit, context.coordinator.appliedEditId != edit.id {
            context.coordinator.appliedEditId = edit.id
            let limit = max((text as NSString).length, 0)
            uiView.selectedRange = NSRange(location: min(max(edit.selection, 0), limit), length: 0)
            if edit.refocus {
                uiView.becomeFirstResponder()
            }
        }
    }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ComposerTextEditor
        var appliedEditId = UUID()

        init(_ parent: ComposerTextEditor) {
            self.parent = parent
        }

        func textViewDidChange(_ textView: UITextView) {
            parent.text = textView.text
            parent.cursor = textView.selectedRange.location
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            parent.cursor = textView.selectedRange.location
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            parent.focused = true
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            parent.focused = false
        }
    }
}
