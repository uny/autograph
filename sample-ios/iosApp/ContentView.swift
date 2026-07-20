import SwiftUI
import UIKit
import sample_shared

/// Hosts `sample-shared`'s `MainViewController()` — the Compose UI (see `App.kt`) that exercises
/// every README snippet against a `LoggingTracker` printing via `NSLog`.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Launch argument that swaps the Compose sample for the SwiftUI-only one.
///
/// A launch argument rather than in-app navigation so the two screens stay independent: the Compose
/// XCUITests launch with no arguments and are unaffected by anything the native screen does, and the
/// native screen is reached without a tap that the native capture would itself observe.
let nativeSampleLaunchArgument = "-autograph-native-sample"

/// Holds what the native capture last reported, so the SwiftUI screen can display it.
///
/// A class rather than `@State` on the view: the callback handed to Kotlin escapes, and a struct's
/// `@State` cannot be written from an escaping closure that captured a copy of the struct.
final class NativeSampleEvents: ObservableObject {
    @Published var lastEvent = "(none yet)"
    /// The full properties JSON of the last reported tap, so a UI test can observe the `screen` /
    /// `section` a native tap was enriched with — not just its target.
    @Published var lastProps = "(none yet)"
}

/// A SwiftUI-only screen — no Compose anywhere in it — exercising `autograph-uikit`'s native tap
/// capture end to end, through real synthetic touches.
///
/// The content is chosen to cover the shapes that have actually broken:
/// - a `List`, whose full-screen `_UITouchPassthroughView` overlay swallowed every tap until #82
/// - enough rows to scroll, because a scroll leaves a touch-begin position behind and the *next* tap
///   used to be resolved against it (#83)
/// - a button carrying no `.accessibilityIdentifier`, which must be dropped rather than reported
///   under some fallback name
struct NativeSampleView: View {
    @StateObject private var events = NativeSampleEvents()

    var body: some View {
        VStack(spacing: 12) {
            Text("Last event target: \(events.lastEvent)")
                .accessibilityIdentifier("native_last_event_label")
            Text("Last event props: \(events.lastProps)")
                .accessibilityIdentifier("native_last_event_props_label")

            Button("Plain") {}
                .accessibilityIdentifier("native_plain_button")

            // Deliberately no .accessibilityIdentifier: there is no stable name to report this
            // under, and identification must not fall back to the displayed text.
            Button("Unidentified") {}

            List(0..<40, id: \.self) { index in
                Button("Row \(index)") {}
                    .accessibilityIdentifier("native_row_\(index)")
            }
            .accessibilityIdentifier("native_list")
        }
        .onAppear {
            NativeSampleCaptureKt.installNativeSampleCapture { target, props in
                events.lastEvent = target
                events.lastProps = props
            }
        }
    }
}

/// Launch argument for the hybrid screen: SwiftUI content and a Compose host in one window, with the
/// native capture running and Compose autocapture off.
let hybridSampleLaunchArgument = "-autograph-hybrid-sample"

/// Hosts `HybridViewController()` — Compose with autocapture *off*, embedded in the native screen.
struct ComposeHybridView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        HybridSampleKt.HybridViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// A hybrid screen: native SwiftUI content and Compose content in the same window, with the native
/// capture installed.
///
/// The boundary this exercises is a privacy invariant, not a de-duplication nicety. Content under a
/// Compose host belongs to the Compose pipeline *exclusively* — not "content the Compose pipeline
/// reported". Here Compose reports nothing at all, so if the native side is not held off the Compose
/// subtree it will happily walk in and report elements whose exclusions it cannot see.
struct HybridSampleView: View {
    @StateObject private var events = NativeSampleEvents()

    var body: some View {
        VStack(spacing: 12) {
            Text("Last event target: \(events.lastEvent)")
                .accessibilityIdentifier("native_last_event_label")

            Button("Native") {}
                .accessibilityIdentifier("native_button_in_hybrid")

            ComposeHybridView()
        }
        .onAppear {
            NativeSampleCaptureKt.installNativeSampleCapture { target, props in
                events.lastEvent = target
                events.lastProps = props
            }
        }
    }
}

/// Launch argument for the UIKit-navigation sample that exercises #65's native screen capture.
let nativeScreensLaunchArgument = "-autograph-native-screens"

struct ContentView: View {
    var body: some View {
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains(nativeSampleLaunchArgument) {
            NativeSampleView()
        } else if arguments.contains(hybridSampleLaunchArgument) {
            HybridSampleView()
        } else if arguments.contains(nativeScreensLaunchArgument) {
            NativeScreensRootView()
        } else if arguments.contains(swiftUIScreensLaunchArgument) {
            SwiftUIScreensView()
        } else {
            ComposeView()
        }
    }
}

// MARK: - UIKit navigation sample for native screen capture (#65)

/// Hosts a real `UINavigationController` stack of UIKit `UIViewController`s, the hierarchy #65's
/// `viewDidAppear:` swizzle actually fires on. A SwiftUI screen cannot serve this: its host is a
/// system-bundle `UIHostingController` the swizzle excludes, and `NavigationStack` swaps destinations
/// inside one host with no per-destination `viewDidAppear:`. Here each push, each presented sheet, and
/// each tab switch is a distinct app controller appearing — exactly what native screen capture reports.
///
/// The capture is installed in `makeUIViewController`, before the hierarchy enters the window, so the
/// very first screen's `viewDidAppear:` is already swizzled and reported.
struct NativeScreensRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        NativeScreensEvents.shared.installOnce()
        return AppNavigationController(rootViewController: FirstScreen())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// An **app-defined** navigation container — the common pattern of subclassing `UINavigationController`.
/// It lives in the app bundle, so the bundle filter alone would let it through; only the container-kind
/// check (which is inheritance-aware) keeps it from reporting itself as a screen on every push. Using it
/// here, rather than a plain `UINavigationController`, is what makes the container exclusion actually
/// load-bearing in the tests: with a stock container the bundle check would mask a broken kind check.
final class AppNavigationController: UINavigationController {}

/// An app-defined tab container, for the same reason. See `AppNavigationController`.
final class AppTabBarController: UITabBarController {}

/// Screen-independent observation state, shared across every screen so the XCUITest suite can read the
/// cumulative screen-view log and the last tap regardless of which screen is on top. Kotlin's capture
/// callbacks write here; whichever screen is currently visible renders it through its own labels.
final class NativeScreensEvents {
    static let shared = NativeScreensEvents()

    private(set) var screenLog = "(none yet)"
    private(set) var lastTarget = "(none yet)"
    private(set) var lastProps = "(none yet)"

    /// Every screen's label trio, held weakly. Each event updates them *all*, so whichever screen is
    /// currently on top — the only one XCUITest can query, since a presented screen fully covers its
    /// presenter — always shows the current shared state. Updating one screen's labels on a "bind on
    /// appear" would miss the presenter after an over-full-screen dismiss, which fires it no appearance
    /// callback; updating all of them sidesteps that entirely.
    private let labelSets = NSHashTable<ScreenLabels>.weakObjects()

    /// Installs the native screen + tap capture once (the swizzle is process-global), wiring both
    /// callbacks to this object.
    func installOnce() {
        NativeScreensCaptureKt.installNativeScreensCapture(
            onScreenLog: { [weak self] log in
                self?.screenLog = log
                self?.renderAll()
            },
            onTap: { [weak self] target, props in
                self?.lastTarget = target
                self?.lastProps = props
                self?.renderAll()
            }
        )
    }

    func register(_ labels: ScreenLabels) {
        labelSets.add(labels)
        labels.apply(self)
    }

    private func renderAll() {
        for labels in labelSets.allObjects {
            labels.apply(self)
        }
    }
}

/// One screen's three observation labels. Held strongly by the screen that owns them, weakly by
/// `NativeScreensEvents`, so a screen's set drops out of the shared table when the screen deallocates.
final class ScreenLabels {
    let screenLog = ScreenLabels.make("native_screen_view_log_label")
    let lastTarget = ScreenLabels.make("native_last_event_label")
    let lastProps = ScreenLabels.make("native_last_event_props_label")

    func apply(_ events: NativeScreensEvents) {
        screenLog.text = "Screen views: \(events.screenLog)"
        lastTarget.text = "Last event target: \(events.lastTarget)"
        lastProps.text = "Last event props: \(events.lastProps)"
    }

    private static func make(_ id: String) -> UILabel {
        let label = UILabel()
        label.numberOfLines = 0
        label.accessibilityIdentifier = id
        return label
    }
}

/// A UIKit screen: a title, screen-specific action buttons, and the three shared observation labels.
/// Concrete screens override `actionViews()`; the class name (module prefix stripped by the Kotlin
/// naming override) is what native screen capture reports.
class NativeScreenBaseViewController: UIViewController {
    private let labels = ScreenLabels()

    /// Buttons/content specific to the concrete screen. Overridden by subclasses.
    func actionViews() -> [UIView] { [] }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .boldSystemFont(ofSize: 22)

        let stack = UIStackView(
            arrangedSubviews: [titleLabel] + actionViews() + [labels.screenLog, labels.lastTarget, labels.lastProps]
        )
        stack.axis = .vertical
        stack.spacing = 12
        stack.alignment = .leading
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
        ])

        NativeScreensEvents.shared.register(labels)
    }

    func actionButton(_ label: String, id: String, _ action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system, primaryAction: UIAction(title: label) { _ in action() })
        button.accessibilityIdentifier = id
        return button
    }
}

/// The navigation root. Pushes a second screen, presents a page sheet, and presents a tab bar.
final class FirstScreen: NativeScreenBaseViewController {
    override func viewDidLoad() {
        title = "First"
        super.viewDidLoad()
    }

    override func actionViews() -> [UIView] {
        [
            actionButton("Push Second", id: "native_push_second") { [weak self] in
                self?.navigationController?.pushViewController(SecondScreen(), animated: false)
            },
            actionButton("Present Sheet", id: "native_present_sheet") { [weak self] in
                let sheet = SheetScreen()
                // .overFullScreen, not .pageSheet: the property under test is that the presenter gets
                // no viewDidDisappear: (so its frame survives underneath and is restored on dismiss),
                // which .overFullScreen preserves exactly — while a page sheet leaves the presenter
                // partly on screen, so its identically-identified observation labels would collide with
                // the presented screen's and the test would read the wrong one. Covering fully keeps the
                // one queryable set of labels the presented screen's, without sending the presenter a
                // disappear it must not get.
                sheet.modalPresentationStyle = .overFullScreen
                self?.present(sheet, animated: false)
            },
            actionButton("Present Tabs", id: "native_present_tabs") { [weak self] in
                let tabs = makeTabsController()
                // .fullScreen: this case only exercises tab-switch screen views, so covering the
                // presenter (and letting it disappear) is fine and keeps one queryable label set.
                tabs.modalPresentationStyle = .fullScreen
                self?.present(tabs, animated: false)
            },
            actionButton("Present Excluded", id: "native_present_excluded") { [weak self] in
                // A SwiftUI screen — a system-bundle UIHostingController the capture excludes. Presented
                // .fullScreen so First genuinely disappears and reappears around it, with nothing
                // capturable recorded in between: the case where a naive `previous_screen` would name
                // First as its own previous.
                let excluded = UIHostingController(
                    rootView: ExcludedModalView { [weak self] in self?.dismiss(animated: false) }
                )
                excluded.modalPresentationStyle = .fullScreen
                self?.present(excluded, animated: false)
            },
            // A plain button so a tap on this screen can be shown to carry screen = "First".
            actionButton("First tap target", id: "native_first_button") {},
        ]
    }
}

/// Pushed onto the navigation stack. Pushing it sends `First` a `viewDidDisappear:` (the frame leaves),
/// popping sends `First` a fresh `viewDidAppear:` (a new screen view, previous_screen = "Second").
final class SecondScreen: NativeScreenBaseViewController {
    override func viewDidLoad() {
        title = "Second"
        super.viewDidLoad()
    }

    override func actionViews() -> [UIView] {
        [
            actionButton("Second tap target", id: "native_second_button") {},
            actionButton("Back", id: "native_pop") { [weak self] in
                self?.navigationController?.popViewController(animated: false)
            },
        ]
    }
}

/// Presented `.overFullScreen`. The presenter (`First`) gets *no* `viewDidDisappear:` on present nor
/// `viewDidAppear:` on dismiss — so a single "current screen" slot would drop to nothing after the
/// sheet closes. The stack model keeps `First`'s frame underneath and restores it when the sheet's
/// frame is removed on dismiss.
final class SheetScreen: NativeScreenBaseViewController {
    override func viewDidLoad() {
        title = "Sheet"
        super.viewDidLoad()
    }

    override func actionViews() -> [UIView] {
        [
            actionButton("Sheet tap target", id: "native_sheet_button") {},
            actionButton("Dismiss", id: "native_dismiss_sheet") { [weak self] in
                self?.dismiss(animated: false)
            },
        ]
    }
}

/// Two tabs in a `UITabBarController` (itself a container, excluded — only the selected content
/// controller is a screen). Switching from `TabA` to `TabB` fires `TabB`'s `viewDidAppear:`.
final class TabAScreen: NativeScreenBaseViewController {
    override func viewDidLoad() {
        title = "TabA"
        super.viewDidLoad()
    }

    override func actionViews() -> [UIView] {
        [actionButton("TabA tap target", id: "native_tab_a_button") {}]
    }
}

final class TabBScreen: NativeScreenBaseViewController {
    override func viewDidLoad() {
        title = "TabB"
        super.viewDidLoad()
    }

    override func actionViews() -> [UIView] {
        [actionButton("TabB tap target", id: "native_tab_b_button") {}]
    }
}

/// A SwiftUI screen presented as an excluded modal — native screen capture reports nothing for it
/// (its host is a system-bundle `UIHostingController`). Its only job is to be an untracked screen the
/// UIKit sample can pass through and return from.
struct ExcludedModalView: View {
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("SwiftUI modal — excluded from native screen capture")
            Button("Dismiss Excluded", action: onDismiss)
                .accessibilityIdentifier("native_dismiss_excluded")
        }
    }
}

private func makeTabsController() -> UITabBarController {
    let tabs = AppTabBarController()
    let a = TabAScreen()
    a.tabBarItem = UITabBarItem(title: "TabA", image: nil, tag: 0)
    let b = TabBScreen()
    b.tabBarItem = UITabBarItem(title: "TabB", image: nil, tag: 1)
    tabs.viewControllers = [a, b]
    return tabs
}

// MARK: - SwiftUI navigation sample for the explicit `.autographScreen` API (#65 PR-D2b)

/// Launch argument for the SwiftUI `NavigationStack` sample that exercises #65's explicit screen API.
let swiftUIScreensLaunchArgument = "-autograph-swiftui-screens"

/// Installs the sample's `AutographScreenCapture` once (in `init`, before any screen appears) and holds
/// the cumulative `name:previous_screen` log for the always-visible label the XCUITest reads.
final class SwiftUIScreensEvents: ObservableObject {
    @Published var screenLog = "(none yet)"

    init() {
        SwiftUIScreensCaptureKt.installSwiftUIScreensCapture { [weak self] log in
            self?.screenLog = log
        }
    }
}

/// The sample's stand-in for the `AutographUI` product's `.autographScreen("Name")` modifier.
///
/// It is **intentionally identical in shape** to the shipped modifier — `onAppear` reports the screen,
/// `onDisappear` retires it. The only difference is bookkeeping: the shipped modifier holds the
/// `AutographScreenView` token in SwiftUI `@State`, whereas this drives `sample-shared`'s facade by
/// name (so no `autograph-uikit`/umbrella type has to cross into the sample, which already links
/// `sample_shared`). What it verifies — the Kotlin facade's emit + `previous_screen` + self-previous
/// guard, driven through a real `NavigationStack` — is the same.
struct AutographSampleScreen: ViewModifier {
    let name: String

    func body(content: Content) -> some View {
        content
            .onAppear { SwiftUIScreensCaptureKt.swiftUIScreenAppeared(name: name) }
            .onDisappear { SwiftUIScreensCaptureKt.swiftUIScreenDisappeared(name: name) }
    }
}

extension View {
    func autographSampleScreen(_ name: String) -> some View {
        modifier(AutographSampleScreen(name: name))
    }
}

/// Guards the `NavigationStack` sample behind iOS 16 (the sample app targets iOS 15). The
/// `.autographScreen` mechanism itself is `onAppear`/`onDisappear`, so it needs no such floor — only
/// this sample's `NavigationStack` container does.
struct SwiftUIScreensView: View {
    var body: some View {
        if #available(iOS 16.0, *) {
            SwiftUIScreensNavigation()
        } else {
            Text("The SwiftUI screens sample needs iOS 16 (NavigationStack).")
        }
    }
}

@available(iOS 16.0, *)
struct SwiftUIScreensNavigation: View {
    @StateObject private var events = SwiftUIScreensEvents()

    var body: some View {
        NavigationStack {
            SwiftUIFirstScreen()
        }
        // Outside the navigation content, so the log stays visible and queryable no matter which screen
        // is pushed on top.
        .safeAreaInset(edge: .bottom) {
            Text("Screen views: \(events.screenLog)")
                .accessibilityIdentifier("swiftui_screen_view_log_label")
                .padding(8)
        }
    }
}

@available(iOS 16.0, *)
struct SwiftUIFirstScreen: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("First").accessibilityIdentifier("swiftui_first_marker")
            NavigationLink("Go to Second") { SwiftUISecondScreen() }
                .accessibilityIdentifier("swiftui_go_second")
            NavigationLink("Go to Untracked") { SwiftUIUntrackedScreen() }
                .accessibilityIdentifier("swiftui_go_untracked")
        }
        .autographSampleScreen("SwiftUIFirst")
    }
}

@available(iOS 16.0, *)
struct SwiftUISecondScreen: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("Second").accessibilityIdentifier("swiftui_second_marker")
            Button("Back") { dismiss() }.accessibilityIdentifier("swiftui_back_second")
        }
        .autographSampleScreen("SwiftUISecond")
    }
}

/// Deliberately carries **no** `.autographSampleScreen` — an untracked intermediate screen, so that
/// returning to First from it records nothing in between and First must not become its own
/// `previous_screen`.
@available(iOS 16.0, *)
struct SwiftUIUntrackedScreen: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("Untracked").accessibilityIdentifier("swiftui_untracked_marker")
            Button("Back") { dismiss() }.accessibilityIdentifier("swiftui_back_untracked")
        }
    }
}
