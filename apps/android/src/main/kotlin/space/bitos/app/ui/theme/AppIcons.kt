package space.bitos.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Gif
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * AppIcons — the application's single source of truth for icons
 * (unified feature spec §2.5, APP-022; ports the legacy Flutter
 * `core/theme/app_icons.dart` token architecture).
 *
 * Solar is BitOS's product icon language. This native facade preserves the
 * semantic token while using Material where that is the better platform
 * convention (especially Android sharing). A reviewed bundled Solar vector
 * may replace an individual backing without changing any feature call site.
 * See docs/native/icon-system.md and THIRD_PARTY_NOTICES.md.
 */
object AppIcons {

    // ── Feed actions ─────────────────────────────────────────────────
    val Heart = Icons.Rounded.Favorite
    val HeartOutline = Icons.Rounded.FavoriteBorder
    val Bookmark = Icons.Rounded.Bookmark
    val BookmarkOutline = Icons.Rounded.BookmarkBorder
    val Repost = Icons.Rounded.Repeat           // Solar: refreshSquare? web: lucide repeat
    val Comment = Icons.Rounded.ChatBubbleOutline // Solar: chatSquare
    val Share = Icons.Rounded.Share
    val Zap = Icons.Rounded.Bolt                // zap amber

    // ── Actions (Flutter AppIcons parity) ────────────────────────────
    val More = Icons.Rounded.MoreHoriz          // Solar: menuDots
    val Check = Icons.Rounded.Check
    val CheckCircle = Icons.Rounded.CheckCircle // Solar: checkCircle (verified badge)
    val Close = Icons.Rounded.Close             // Solar: closeCircle
    val Copy = Icons.Rounded.ContentCopy        // Solar: copy
    val Delete = Icons.Rounded.Delete           // Solar: trashBinMinimalistic
    val Send = Icons.AutoMirrored.Rounded.Send  // Solar: plain
    val Back = Icons.AutoMirrored.Rounded.ArrowBack // chat / subpage back
    val Lock = Icons.Rounded.Lock              // NIP-17 encryption indicator
    val Pen = Icons.Rounded.Edit                 // compose / new note (Solar: pen)
    val Refresh = Icons.Rounded.Refresh         // Solar: refresh
    val ArrowUp = Icons.Rounded.ArrowUpward      // new-notes reveal (X parity)
    val Add = Icons.Rounded.Add                 // Solar: addCircle
    val Search = Icons.Rounded.Search
    val Filter = Icons.Rounded.Tune             // feed content filter
    val AppsGrid = Icons.Rounded.GridView       // More hub entry

    // ── Moderation / safety (spec §3.5 card menus) ───────────────────
    val Mute = Icons.Rounded.VolumeOff
    val Poll = Icons.Rounded.HowToVote             // poll composer (legacy parity)
    val ReportSpam = Icons.Rounded.Warning
    val ReportIllicit = Icons.Rounded.ErrorOutline
    val ReportHarassment = Icons.Rounded.PanTool
    val Block = Icons.Rounded.Close             // Solar: closest: closeCircle

    // ── Bitz player controls (spec §3.7) ─────────────────────────────
    val SoundOn = Icons.AutoMirrored.Rounded.VolumeUp   // unmuted state
    val Pause = Icons.Rounded.Pause
    val Back10 = Icons.Rounded.Replay10    // −10 s seek pill
    val Forward10 = Icons.Rounded.Forward10 // +10 s seek pill

    // ── Identity / navigation ────────────────────────────────────────
    val User = Icons.Rounded.Person             // Solar: user
    val QrCode = Icons.Rounded.QrCode2          // Solar: qrCode
    val Home = Icons.Rounded.Home
    val Discover = Icons.Rounded.Search
    val Create = Icons.Rounded.Add

    // Home feed tabs (legacy Flutter parity: sparkles / users).
    val Sparkles = Icons.Rounded.AutoAwesome
    val People = Icons.Rounded.People
    val Inbox = Icons.Rounded.Notifications
    val Chat = Icons.Rounded.ChatBubble
    val Globe = Icons.Rounded.Public            // Solar: global
    val Logout = Icons.Rounded.ExitToApp

    // ── Discover (prototype `#/discover` parity) ────────────────────
    val Flame = Icons.Rounded.LocalFireDepartment // trending-hot hashtag
    val Hash = Icons.Rounded.Numbers                // hashtag glyph
    val ChevronRight = Icons.Rounded.ChevronRight // list-row affordance
    val ChevronLeft = Icons.Rounded.ChevronLeft   // list-row affordance (mirrored)

    // ── Overlay manipulation (MUX-03 selection controls) ────────────
    val NudgeLeft = Icons.Rounded.ChevronLeft       // Solar: altArrowLeft
    val NudgeRight = Icons.Rounded.ChevronRight     // Solar: altArrowRight
    val NudgeUp = Icons.Rounded.KeyboardArrowUp     // Solar: altArrowUp
    val NudgeDown = Icons.Rounded.KeyboardArrowDown // Solar: altArrowDown
    val ZoomOut = Icons.Rounded.ZoomOut             // Solar: magniferZoomOut
    val ZoomIn = Icons.Rounded.ZoomIn               // Solar: magniferZoomIn

    // ── Studio / Create hub (spec §3.17/§3.19) ───────────────────────
    val MusicNote = Icons.Rounded.MusicNote     // Solar: music (sound library)
    val Remix = Icons.Rounded.AutoFixHigh       // Solar: magicWand (studio remix)
    val Undo = Icons.AutoMirrored.Rounded.Undo  // Solar: undo (editor history)
    val Redo = Icons.AutoMirrored.Rounded.Redo  // Solar: undoRight (suite dock)
    val Download = Icons.Rounded.Download       // Solar: save (editor export)
    val SaveDraft = Icons.Rounded.Save          // editor project draft (distinct from export)
    val Scissors = Icons.Rounded.ContentCut     // V2 suite: Trim tool chip
    val Speed = Icons.Rounded.Speed             // V2 suite: Speed tool chip
    val Film = Icons.Rounded.Movie              // prototype editor: Split clip tool
    val Layer = Icons.Rounded.Layers            // prototype editor: Layer clip tool
    val Loop = Icons.Rounded.Loop               // prototype editor: GIF loop bar slot
    val Crop = Icons.Rounded.Crop               // prototype editor: Crop bar slot (wave 2)
    val Ratio = Icons.Rounded.AspectRatio       // prototype editor: Canvas/Ratio bar slot
    val Looks = Icons.Rounded.Palette           // prototype editor: Filter/Effects bar slot
    val TextGlyph = Icons.Rounded.TextFields    // prototype editor: Text bar slot
    val Wallpaper = Icons.Rounded.Wallpaper     // prototype editor: Background bar slot

    // ── Media ────────────────────────────────────────────────────────
    val Camera = Icons.Rounded.CameraAlt
    val Photo = Icons.Rounded.PhotoLibrary
    val Video = Icons.Rounded.Videocam
    val Play = Icons.Rounded.PlayArrow
    val PlayCircle = Icons.Rounded.PlayCircle   // Solar: playCircle (video badge)
    val Gif = Icons.Rounded.Gif                       // GIF picker (legacy parity)
    val BrokenImage = Icons.Rounded.BrokenImage       // failed media tile
    val Link = Icons.Rounded.Link                     // Solar: linkCircle (profile link)
    val Compass = Icons.Rounded.Explore          // Solar: compass (public stories lane)
    val Visibility = Icons.Rounded.Visibility        // own-story view count
    val VisibilityOff = Icons.Rounded.VisibilityOff  // Solar: eyeClosed (sensitive gate)

    /** Convenience for components taking optional leading icons. */
    val none: ImageVector? = null
}
