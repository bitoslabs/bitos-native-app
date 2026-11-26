import SwiftUI

/**
 * AppIcons — the application's single source of truth for icons
 * (unified feature spec §2.5, APP-022).
 *
 * Resolves semantic tokens directly to native SF Symbols.
 * Naming: filled variant = active/on states; outline = inactive.
 */
enum AppIcons {
    /// Resolves a semantic token to its native SF Symbol.
    static func image(for symbol: String) -> Image {
        Image(systemName: symbol)
    }

    // ── Feed actions (Flutter: heart/bookmark/repost/comment/share) ──
    static let heart = "heart"
    static let heartFill = "heart.fill"
    static let bookmark = "bookmark"
    static let bookmarkFill = "bookmark.fill"
    static let repost = "arrow.2.squarepath"          // Solar: refreshSquare? web: lucide repeat
    static let comment = "bubble.right"               // Solar: chatSquare
    static let share = "square.and.arrow.up"
    static let zap = "bolt.fill"                      // Solar: bolt; zap amber

    // ── Actions (Flutter AppIcons parity) ────────────────────────────
    static let more = "ellipsis"                      // Solar: menuDots
    static let check = "checkmark"
    static let checkCircle = "checkmark.circle.fill"  // Solar: checkCircle
    static let close = "xmark"                        // Solar: closeCircle
    static let copy = "doc.on.doc"                    // Solar: copy
    static let delete = "trash"                       // Solar: trashBinMinimalistic
    static let send = "paperplane.fill"               // Solar: plain
    static let pen = "pencil"                         // compose / new note
    static let refresh = "arrow.clockwise"            // Solar: refresh
    static let undo = "arrow.uturn.backward"          // Solar: undo (editor history)
    static let redo = "arrow.uturn.forward"           // editor history (SF Symbol; no Solar redo asset bundled)
    static let save = "square.and.arrow.down"         // Solar: save (editor export to Photos)
    // ── Studio / Create hub (spec §3.17/§3.19) ───────────────────────
    static let musicNote = "music.note"
    static let remix = "sparkles"
    static let textStyle = "textformat"
    static let sticker = "face.smiling"
    static let looks = "paintpalette"
    static let sfx = "waveform"
    static let warningTriangle = "exclamationmark.triangle"
    static let add = "plus"                           // Solar: addCircle
    static let search = "magnifyingglass"
    static let filter = "line.3.horizontal.decrease"  // feed content filter
    static let appsGrid = "square.grid.2x2"           // More hub entry
    static let arrowUp = "arrow.up"                   // new-notes reveal (X parity; Solar swap-in later)
    static let settings = "gearshape"

    // Home feed tabs (legacy Flutter parity: sparkles / users).
    static let sparkles = "sparkles"                   // For you
    static let people = "person.2"                     // Following

    // ── Moderation / safety (spec §3.5 card menus) ───────────────────
    static let mute = "speaker.slash"
    static let reportSpam = "exclamationmark.bubble"
    static let reportIllicit = "exclamationmark.octagon"
    static let reportHarassment = "hand.raised"
    static let block = "hand.raised.slash"            // Solar: closest: closeCircle

    // ── Composer toolbar (web Composer parity: shield-check PoW,
    // eye-off sensitive, bar-chart poll, film GIF, hash, smile) ──────
    static let shieldCheck = "checkmark.shield"
    static let eyeClosed = "eye.slash"
    static let eye = "eye"                             // own-story view count
    static let chart = "chart.bar"
    static let hashtag = "number"
    static let emoji = "face.smiling"
    static let gifFilm = "film"
    static let link = "link"

    // ── Identity / navigation ────────────────────────────────────────
    static let user = "person.fill"                   // Solar: user
    static let userProfile = "person.crop.circle"
    static let qrCode = "qrcode"                      // Solar: qrCode
    static let home = "house.fill"                  // Home tab (notes feed)
    static let bitz = "play.rectangle.fill"           // Bitz tab (reels)
    static let discover = "magnifyingglass"
    static let create = "plus.square.fill"
    static let inbox = "bell.fill"
    static let chat = "message.fill"                  // DMs
    static let lock = "lock.fill"                     // NIP-17 encryption indicator
    static let chevronLeft = "chevron.left"            // chat / subpage back
    static let chevronRight = "chevron.right"          // disclosure rows
    static let globe = "globe"                        // Solar: global
    static let logout = "rectangle.portrait.and.arrow.right"

    // ── Media ────────────────────────────────────────────────────────
    static let camera = "camera.fill"
    static let photo = "photo.on.rectangle"
    static let video = "video.fill"
    static let play = "play.fill"
    static let playCircle = "play.circle.fill"   // Solar: playCircle (video badge)
    static let pause = "pause.fill"
    static let brokenImage = "photo.badge.exclamationmark"  // failed media tile
    static let info = "info.circle"                          // picker footer
    static let branch = "arrow.triangle.branch"              // orphan reply indicator

    // ── Bitz player controls (spec §3.7) ─────────────────────────────
    static let soundOn = "speaker.wave.2.fill"        // unmuted state
    static let back10 = "gobackward.10"               // −10 s seek pill
    static let forward10 = "goforward.10"             // +10 s seek pill

    // ── Camera capture chrome (spec §3.19 record screen) ─────────────
    static let torch = "flashlight.on.fill"
    static let timer = "timer"
    static let clockCircle = "clock"
    static let cameraRotate = "arrow.triangle.2.circlepath.camera"
    static let gridGuides = "square.grid.3x3"

    // ── Editor shell (MSU-014): one glyph per tool, no collisions ─────
    static let captions = "captions.bubble"           // classic top/bottom captions
    static let timeline = "timeline.selection"        // timeline workspace entry
    static let frameTray = "rectangle.stack"          // GIF frame tray
    static let gifLibrary = "photo.stack"             // GIF library browse
    static let durationTimer = "timer"                // duration / frame hold
    static let keyboardKeys = "keyboard"              // shortcuts reference
    static let split = "scissors.badge.ellipsis"      // split clip at playhead
    static let schedule = "clock.arrow.circlepath"    // overlay timing window
    static let moreIcon = "ellipsis"                  // More overflow

    /// Catalogue icon key → the platform glyph (MSU-014 parity table).
    /// Keys come from `MemeTools.ToolId.iconKey` via the bridge.
    static func symbol(forCatalogIcon key: String) -> String {
        switch key {
        case "media": return photo
        case "text": return textStyle
        case "sticker": return sticker
        case "sound": return sfx
        case "look": return looks
        case "captions": return captions
        case "canvas": return "rectangle.on.rectangle"
        case "layers": return "square.3.layers.3d"
        case "draw": return pen
        case "trim": return "scissors"
        case "speed": return durationTimer
        case "clips": return video
        case "timeline": return timeline
        case "volume": return "speaker.wave.2"
        case "sfx": return sfx
        case "frames": return frameTray
        case "gifs": return gifLibrary
        case "duration": return durationTimer
        case "batch": return remix
        case "shortcuts": return keyboardKeys
        case "edit": return pen
        case "time": return schedule
        case "duplicate": return "square.on.square"
        case "replace": return "arrow.triangle.2.circlepath"
        case "forward": return "square.2.layers.3d.top.filled"
        case "backward": return "square.2.layers.3d.bottom.filled"
        case "split": return split
        case "mute": return "speaker.slash"
        case "hold": return durationTimer
        case "move-left": return "chevron.left"
        case "move-right": return "chevron.right"
        case "delete": return "trash"
        case "more": return moreIcon
        default: return "circle"
        }
    }
}
