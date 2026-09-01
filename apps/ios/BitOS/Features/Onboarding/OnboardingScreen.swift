import BusinessCore
import SwiftUI
import UIKit

/// `hasOnboarded` persistence (UserDefaults, device-local).
enum OnboardingPrefs {
    private static let key = "bitos_has_onboarded"

    static var hasOnboarded: Bool {
        UserDefaults.standard.bool(forKey: key)
    }

    static func markOnboarded() {
        UserDefaults.standard.set(true, forKey: key)
    }
}

/// Bridge mirror of the shared identity-onboarding content contract
/// (spec §4) — decoded once; every screen renders the shared copy verbatim.
struct IdentityOnboardingMirror {
    struct ValueProp: Identifiable {
        let id: String
        let iconToken: String
        let title: String
        let body: String
    }

    struct Method: Identifiable {
        let id: String
        let iconToken: String
        let title: String
        let subtitle: String
        let recommended: Bool
        let available: Bool
        let androidOnly: Bool
    }

    static let shared = IdentityOnboardingMirror()

    let appName: String
    let tagline: String
    let privacyFootnote: String
    let valueProps: [ValueProp]
    let methodTitle: String
    let methodBody: String
    let methodInfo: String
    let nip46ComingSoon: String
    let methods: [Method]
    let importFieldLabel: String
    let importReviewNote: String
    let addAccountTitle: String
    let addAccountSubtitle: String
    let addAccountReviewLabel: String
    let addAccountCreateLabel: String
    let addAccountCancelLabel: String
    let nsecHelpTitle: String
    let nsecHelpItems: [String]
    let backupWarning: String
    let backupFieldLabel: String
    let backupRevealPrompt: String
    let backupAdvice: String
    let backupNextStepsTitle: String
    let backupNextSteps: [String]
    let backupAckLabel: String
    let backupConfirmLabel: String
    let verifyTitle: String
    let verifyBody: String
    let verifyConfirmLabel: String
    let successTitle: String
    let successBody: String
    let successProfileNote: String
    let successInfo: String
    let successDoneLabel: String

    private init() {
        // KMP returns an NSDictionary; the conditional cast pins the typed view.
        let raw = BusinessCoreBridge().identityOnboardingContent() as? [String: Any] ?? [:]
        func str(_ key: String) -> String { raw[key] as? String ?? "" }
        appName = str("appName")
        tagline = str("tagline")
        privacyFootnote = str("privacyFootnote")
        methodTitle = str("methodTitle")
        methodBody = str("methodBody")
        methodInfo = str("methodInfo")
        nip46ComingSoon = str("nip46ComingSoon")
        importFieldLabel = str("importFieldLabel")
        importReviewNote = str("importReviewNote")
        addAccountTitle = str("addAccountTitle")
        addAccountSubtitle = str("addAccountSubtitle")
        addAccountReviewLabel = str("addAccountReviewLabel")
        addAccountCreateLabel = str("addAccountCreateLabel")
        addAccountCancelLabel = str("addAccountCancelLabel")
        nsecHelpTitle = str("nsecHelpTitle")
        nsecHelpItems = raw["nsecHelpItems"] as? [String] ?? []
        backupWarning = str("backupWarning")
        backupFieldLabel = str("backupFieldLabel")
        backupRevealPrompt = str("backupRevealPrompt")
        backupAdvice = str("backupAdvice")
        backupNextStepsTitle = str("backupNextStepsTitle")
        backupAckLabel = str("backupAckLabel")
        backupConfirmLabel = str("backupConfirmLabel")
        verifyTitle = str("verifyTitle")
        verifyBody = str("verifyBody")
        verifyConfirmLabel = str("verifyConfirmLabel")
        successTitle = str("successTitle")
        successBody = str("successBody")
        successProfileNote = str("successProfileNote")
        successInfo = str("successInfo")
        successDoneLabel = str("successDoneLabel")
        valueProps = (raw["valueProps"] as? [[String: Any]] ?? []).compactMap { map in
            guard let id = map["id"] as? String,
                  let icon = map["icon"] as? String,
                  let title = map["title"] as? String,
                  let body = map["body"] as? String else { return nil }
            return ValueProp(id: id, iconToken: icon, title: title, body: body)
        }
        methods = (raw["methods"] as? [[String: Any]] ?? []).compactMap { map in
            guard let id = map["id"] as? String,
                  let icon = map["icon"] as? String,
                  let title = map["title"] as? String,
                  let subtitle = map["subtitle"] as? String else { return nil }
            return Method(
                id: id,
                iconToken: icon,
                title: title,
                subtitle: subtitle,
                recommended: map["recommended"] as? Bool ?? false,
                available: map["available"] as? Bool ?? true,
                androidOnly: map["androidOnly"] as? Bool ?? false
            )
        }
        backupNextSteps = raw["backupNextSteps"] as? [String] ?? []
    }
}

private enum OnboardingFlowStep {
    case welcome
    case method
    case importKey
    case backup
    case verify
    case success
}

/**
 * Spec §4 launch flow (docs/ui/app-01-onboarding-identity.html): Welcome →
 * Add identity → Import/Backup gate → npub confirmation. Replaces the
 * APP-002 carousel; the identity transaction itself runs through
 * `IdentityStore` exactly like the You-tab and More-hub paths.
 */
struct OnboardingScreen: View {
    let store: IdentityStore
    let onDone: () -> Void

    @State private var step: OnboardingFlowStep = .welcome
    @State private var importInput = ""
    @State private var comingSoonNotice: String?
    private let content = IdentityOnboardingMirror.shared

    var body: some View {
        ZStack {
            Group {
                switch step {
                case .welcome:
                    WelcomeStep(
                        onAddIdentity: { step = .method },
                        onBrowse: onDone
                    )
                case .method:
                    MethodStep(
                        onBack: { step = .welcome },
                        onCreate: {
                            store.createKeyPreview()
                            step = .backup
                        },
                        onImport: { step = .importKey },
                        onComingSoon: { notice in
                            comingSoonNotice = notice
                        }
                    )
                case .importKey:
                    ImportStep(
                        onBack: { step = .method },
                        input: $importInput,
                        error: store.importError,
                        onEdit: { store.clearImportError() },
                        onReview: {
                            store.importKeyPreview(importInput)
                        }
                    )
                case .backup:
                    BackupStep(
                        onBack: {
                            store.cancelPreview()
                            step = .method
                        },
                        secretNsec: store.previewSecretNsec,
                        busy: store.busy,
                        onConfirm: { store.confirmPreview() }
                    )
                case .verify:
                    VerifyStep(
                        onBack: {
                            store.cancelPreview()
                            step = .importKey
                        },
                        npub: store.preview?.npub,
                        busy: store.busy,
                        onConfirm: { store.confirmPreview() }
                    )
                case .success:
                    SuccessStep(
                        account: store.account,
                        onDone: onDone
                    )
                }
            }
            if let notice = comingSoonNotice {
                VStack {
                    Spacer()
                    Text(notice)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(BitOSTheme.textPrimary)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(
                            Capsule().fill(BitOSTheme.surfaceOverlay)
                        )
                        .overlay(Capsule().strokeBorder(BitOSTheme.border, lineWidth: 1))
                        .padding(.bottom, 24)
                        .padding(.horizontal, 20)
                        .task {
                            try? await Task.sleep(for: .seconds(3.5))
                            comingSoonNotice = nil
                        }
                }
            }
        }
        .background(BitOSTheme.background)
        .preferredColorScheme(BitOSTheme.preferredScheme)
        // Confirmation closes the loop: once the preview is sealed into the
        // keychain the success screen finishes the flow. Import advance is
        // also driven from the preview: derivation is async (off-main), so
        // the verify step reacts when the preview lands.
        .onChange(of: store.preview) { _, preview in
            if preview != nil {
                if step == .importKey { step = .verify }
                return
            }
            switch step {
            case .backup:
                step = store.account != nil ? .success : .method
            case .verify:
                step = store.account != nil ? .success : .importKey
            default:
                break
            }
        }
    }
}

// ── 1. Welcome / value proposition ─────────────────────────────────────

private struct WelcomeStep: View {
    let onAddIdentity: () -> Void
    let onBrowse: () -> Void
    private let content = IdentityOnboardingMirror.shared

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 56)
                    ZStack {
                        HexShape().fill(BitOSTheme.accent).frame(width: 96, height: 96)
                        HexShape().fill(Color(hex: 0x14141D)).frame(width: 90, height: 90)
                        Text("₿")
                            .font(.system(size: 40, weight: .heavy))
                            .foregroundStyle(BitOSTheme.accent)
                    }
                    .accessibilityHidden(true)
                    .padding(.bottom, 28)
                    // Official wordmark (light/dark asset variants) replaces
                    // the legacy text title — same brand moment as the shell.
                    Image("Wordmark")
                        .resizable()
                        .scaledToFit()
                        .frame(height: 30)
                        .accessibilityLabel(content.appName)
                    Text(content.tagline)
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 12)
                        .padding(.horizontal, 8)
                    VStack(alignment: .leading, spacing: 16) {
                        ForEach(content.valueProps) { prop in
                            ValuePropRow(prop: prop)
                        }
                    }
                    .padding(.top, 40)
                    .padding(.horizontal, 4)
                }
                .padding(.horizontal, 20)
            }
            VStack(spacing: 10) {
                PrimaryFlowButton(title: "Add identity", action: onAddIdentity)
                GhostFlowButton(title: "Browse now", action: onBrowse)
                Text(content.privacyFootnote)
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 28)
        }
    }
}

private struct ValuePropRow: View {
    let prop: IdentityOnboardingMirror.ValueProp

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            HexIcon(
                systemName: onboardingSymbol(prop.iconToken),
                size: 44,
                background: BitOSTheme.accent.opacity(0.16),
                foreground: BitOSTheme.accent
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(prop.title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                Text(prop.body)
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// ── 2. Identity method ─────────────────────────────────────────────────

private struct MethodStep: View {
    let onBack: () -> Void
    let onCreate: () -> Void
    let onImport: () -> Void
    let onComingSoon: (String) -> Void
    private let content = IdentityOnboardingMirror.shared

    var body: some View {
        VStack(spacing: 0) {
            OnboardingTopNav(title: "Add identity", onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text(content.methodTitle)
                        .font(.system(size: 24, weight: .heavy))
                        .foregroundStyle(BitOSTheme.textPrimary)
                    Text(content.methodBody)
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textSecondary)
                    VStack(spacing: 10) {
                        ForEach(content.methods.filter { !$0.androidOnly }) { method in
                            MethodCard(method: method) {
                                switch method.id {
                                case "create": onCreate()
                                case "import": onImport()
                                case "nip46": onComingSoon(content.nip46ComingSoon)
                                default: break
                                }
                            }
                        }
                    }
                    .padding(.top, 14)
                    StateBannerView(tone: .info, text: content.methodInfo)
                        .padding(.top, 14)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
            }
        }
    }
}

private struct MethodCard: View {
    let method: IdentityOnboardingMirror.Method
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .center, spacing: 16) {
                HexIcon(
                    systemName: onboardingSymbol(method.iconToken),
                    size: 48,
                    background: BitOSTheme.accent.opacity(method.available ? 0.16 : 0.08),
                    foreground: method.available ? BitOSTheme.accent : BitOSTheme.textTertiary
                )
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(method.title)
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(method.available ? BitOSTheme.textPrimary : BitOSTheme.textSecondary)
                        if method.recommended {
                            AssistChipLabel(text: "Recommended")
                        } else if !method.available {
                            AssistChipLabel(text: "Soon")
                        }
                    }
                    Text(method.subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 4)
                if method.available {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(BitOSTheme.textTertiary)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(BitOSTheme.surface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(BitOSTheme.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(method.title)
    }
}

// ── 3. Import secret key ───────────────────────────────────────────────

private struct ImportStep: View {
    let onBack: () -> Void
    @Binding var input: String
    let error: String?
    let onEdit: () -> Void
    let onReview: () -> Void
    private let content = IdentityOnboardingMirror.shared

    private var check: BusinessCoreBridge.KeyImportCheckWire {
        BusinessCoreBridge().keyImportCheck(raw: input)
    }

    var body: some View {
        VStack(spacing: 0) {
            OnboardingTopNav(title: "Import secret key", onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    FlowFieldLabel(text: content.importFieldLabel)
                    SecretKeyField(
                        text: $input,
                        error: error,
                        onSubmit: onReview
                    )
                    .onChange(of: input) { _, _ in onEdit() }
                    if check.verdict == "READY" {
                        DerivedIdentityCard(check: check)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
            }
            VStack(spacing: 10) {
                PrimaryFlowButton(
                    title: "Review key",
                    enabled: check.verdict == "READY",
                    action: onReview
                )
                Text(content.importReviewNote)
                    .font(.system(size: 11))
                    .foregroundStyle(BitOSTheme.textTertiary)
                    .multilineTextAlignment(.center)
            }
            .padding(20)
        }
    }
}

// ── 4. Backup gate (freshly generated key) ─────────────────────────────

private struct BackupStep: View {
    let onBack: () -> Void
    let secretNsec: String?
    let busy: Bool
    let onConfirm: () -> Void
    private let content = IdentityOnboardingMirror.shared

    @State private var revealed = false
    @State private var savedAcknowledged = false

    var body: some View {
        VStack(spacing: 0) {
            OnboardingTopNav(title: "Back up your key", onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    StateBannerView(tone: .warn, text: content.backupWarning)
                    VStack(alignment: .leading, spacing: 6) {
                        FlowFieldLabel(text: content.backupFieldLabel)
                        backupCard
                        Text(content.backupAdvice)
                            .font(.system(size: 11))
                            .foregroundStyle(BitOSTheme.textTertiary)
                            .padding(.top, 4)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        FlowFieldLabel(text: content.backupNextStepsTitle)
                        VStack(spacing: 0) {
                            ForEach(Array(content.backupNextSteps.enumerated()), id: \.offset) { index, stepText in
                                if index > 0 { Divider().overlay(BitOSTheme.divider) }
                                HStack(spacing: 12) {
                                    ZStack {
                                        HexShape().fill(BitOSTheme.accent.opacity(0.16))
                                        Text("\(index + 1)")
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundStyle(BitOSTheme.accent)
                                    }
                                    .frame(width: 30, height: 30)
                                    Text(stepText)
                                        .font(.system(size: 12))
                                        .foregroundStyle(BitOSTheme.textSecondary)
                                }
                                .padding(.horizontal, 14)
                                .padding(.vertical, 12)
                            }
                        }
                        .background(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(BitOSTheme.surface)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .strokeBorder(BitOSTheme.border, lineWidth: 1)
                        )
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
            }
            VStack(spacing: 12) {
                AcknowledgementRow(
                    title: content.backupAckLabel,
                    checked: $savedAcknowledged
                )
                PrimaryFlowButton(
                    title: content.backupConfirmLabel,
                    enabled: savedAcknowledged && !busy && secretNsec != nil,
                    action: onConfirm
                )
            }
            .padding(20)
        }
    }

    /// CB-4: the secret crosses to the view only inside this reveal
    /// transaction; never logged or persisted. Reveal is explicit.
    @ViewBuilder
    private var backupCard: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(BitOSTheme.surface)
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(BitOSTheme.border, lineWidth: 1)
            if revealed, let secretNsec {
                VStack(alignment: .leading, spacing: 12) {
                    Text(secretNsec)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color(red: 1, green: 0.65, blue: 0.16)) // --orange-hi
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    HStack(spacing: 8) {
                        GhostPillFlowButton(title: "Copy key") {
                            UIPasteboard.general.string = secretNsec
                        }
                        GhostPillFlowButton(title: "Hide") {
                            withAnimation(.easeOut(duration: 0.15)) { revealed = false }
                        }
                    }
                }
                .padding(16)
            } else {
                Button {
                    withAnimation(.easeOut(duration: 0.15)) { revealed = true }
                } label: {
                    VStack(spacing: 8) {
                        Image(systemName: "lock")
                            .font(.system(size: 20))
                            .foregroundStyle(BitOSTheme.textSecondary)
                        Text(content.backupRevealPrompt)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(BitOSTheme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(content.backupRevealPrompt)
            }
        }
    }
}

/// CB-2: the backup gate only opens once the user owns the consequence.
private struct AcknowledgementRow: View {
    let title: String
    @Binding var checked: Bool

    var body: some View {
        Button {
            checked.toggle()
        } label: {
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(BitOSTheme.surfaceElevated)
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .strokeBorder(BitOSTheme.border, lineWidth: 1)
                    if checked {
                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(BitOSTheme.accent)
                    }
                }
                .frame(width: 24, height: 24)
                Text(title)
                    .font(.system(size: 12))
                    .foregroundStyle(BitOSTheme.textSecondary)
                    .multilineTextAlignment(.leading)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(checked ? [.isSelected] : [])
    }
}

// ── 5a. Verification (imported key) ────────────────────────────────────

private struct VerifyStep: View {
    let onBack: () -> Void
    let npub: String?
    let busy: Bool
    let onConfirm: () -> Void
    private let content = IdentityOnboardingMirror.shared

    var body: some View {
        VStack(spacing: 0) {
            OnboardingTopNav(title: content.verifyTitle, onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(content.verifyBody)
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textSecondary)
                    NpubFlowCard(npub: npub)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
            }
            PrimaryFlowButton(
                title: content.verifyConfirmLabel,
                enabled: !busy && npub != nil,
                action: onConfirm
            )
            .padding(20)
        }
    }
}

// ── 5b. Identity ready ─────────────────────────────────────────────────

private struct SuccessStep: View {
    let account: AccountIdentity?
    let onDone: () -> Void
    private let content = IdentityOnboardingMirror.shared

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 56)
                    ZStack {
                        HexShape().fill(BitOSTheme.success.opacity(0.14))
                        Image(systemName: "checkmark")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundStyle(BitOSTheme.success)
                    }
                    .frame(width: 80, height: 80)
                    .accessibilityHidden(true)
                    Text(content.successTitle)
                        .font(.system(size: 24, weight: .heavy))
                        .foregroundStyle(BitOSTheme.textPrimary)
                        .padding(.top, 24)
                    Text(content.successBody)
                        .font(.system(size: 14))
                        .foregroundStyle(BitOSTheme.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                    VStack(alignment: .leading, spacing: 14) {
                        NpubFlowCard(npub: account?.npub)
                        HStack(spacing: 12) {
                            if let account {
                                HexAvatarView(pubkey: account.pubkeyHex, size: 44)
                            }
                            Text(content.successProfileNote)
                                .font(.system(size: 11))
                                .foregroundStyle(BitOSTheme.textTertiary)
                        }
                    }
                    .padding(16)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(BitOSTheme.surface)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(BitOSTheme.border, lineWidth: 1)
                    )
                    .padding(.top, 32)
                    StateBannerView(tone: .info, text: content.successInfo)
                        .padding(.top, 20)
                }
                .padding(.horizontal, 24)
            }
            PrimaryFlowButton(title: content.successDoneLabel, action: onDone)
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 28)
        }
    }
}

// ── Shared flow atoms ──────────────────────────────────────────────────

private struct OnboardingTopNav: View {
    let title: String
    let onBack: () -> Void

    var body: some View {
        HStack {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back")
            Spacer()
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(BitOSTheme.textPrimary)
            Spacer()
            Color.clear.frame(width: 40, height: 40)
        }
        .padding(.horizontal, 8)
        .frame(height: 56)
    }
}

private struct FlowFieldLabel: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 11, weight: .bold))
            .kerning(0.8)
            .foregroundStyle(BitOSTheme.textTertiary)
    }
}

private struct AssistChipLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(BitOSTheme.accent)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(Capsule().fill(BitOSTheme.accent.opacity(0.14)))
            .overlay(Capsule().strokeBorder(BitOSTheme.accent.opacity(0.4), lineWidth: 1))
    }
}

private struct PrimaryFlowButton: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .heavy))
                .foregroundStyle(Color(hex: 0x1A1000))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(enabled ? BitOSTheme.accent : BitOSTheme.accent.opacity(0.45))
                )
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(title)
    }
}

private struct GhostFlowButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(BitOSTheme.textPrimary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(BitOSTheme.surfaceElevated)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(BitOSTheme.border, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

private struct GhostPillFlowButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(BitOSTheme.textPrimary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
                .background(Capsule().fill(BitOSTheme.surfaceElevated))
                .overlay(Capsule().strokeBorder(BitOSTheme.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}

/// npub display + copy card used by verification and success.
private struct NpubFlowCard: View {
    let npub: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FlowFieldLabel(text: "Public key (npub)")
            HStack(spacing: 10) {
                Text(npub.map { middleEllipsized($0) } ?? "—")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(BitOSTheme.textPrimary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(BitOSTheme.surfaceElevated)
                    )
                Button {
                    if let npub { UIPasteboard.general.string = npub }
                } label: {
                    Text("Copy")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(BitOSTheme.accent)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Capsule().fill(BitOSTheme.accent.opacity(0.14)))
                        .overlay(Capsule().strokeBorder(BitOSTheme.accent.opacity(0.4), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .disabled(npub == nil)
                .accessibilityLabel("Copy npub")
            }
        }
    }
}

/// Semantic icon tokens from the shared content contract → SF Symbols.
private func onboardingSymbol(_ token: String) -> String {
    switch token {
    case "key": "key.fill"
    case "zap": "bolt.fill"
    case "video": "play.rectangle.fill"
    case "sparkle": "sparkles"
    case "download": "square.and.arrow.down"
    case "link": "link"
    case "android": "cpu"
    default: "key.fill"
    }
}
